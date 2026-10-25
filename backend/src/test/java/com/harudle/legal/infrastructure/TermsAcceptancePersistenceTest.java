package com.harudle.legal.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.legal.application.InvalidTermsAcceptanceException;
import com.harudle.legal.application.LegalDocumentCatalog;
import com.harudle.legal.application.LegalDocumentChangedException;
import com.harudle.legal.application.TermsAcceptance;
import com.harudle.legal.application.TermsAcceptanceCommand;
import com.harudle.legal.application.TermsAcceptanceService;
import com.harudle.legal.application.TermsAcceptanceStatus;
import com.harudle.legal.domain.LegalDocumentType;
import com.harudle.support.AdminModerationDatabaseTestSupport;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties = "harudle.legal.require-terms-acceptance=true")
class TermsAcceptancePersistenceTest extends AdminModerationDatabaseTestSupport {
    private static final Instant NOW = Instant.parse("2026-10-25T06:00:00.123456Z");
    private static final Instant LATER = NOW.plusSeconds(60);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LegalDocumentCatalog catalog;
    @Autowired private TermsAcceptanceService service;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean(name = "authClock", enforceOverride = true) private Clock clock;
    @MockitoSpyBean private JdbcTermsAcceptanceRepository repository;

    private final List<UUID> createdUsers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
    }

    @AfterEach
    void cleanUp() {
        createdUsers.forEach(id -> jdbc.update("DELETE FROM users WHERE id=?", id));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 365})
    @DisplayName("신규·기존 회원 모두 수락 기록을 추정해 만들지 않고 미수락으로 조회한다")
    void newAndExistingMembersRequireTheirOwnAcceptance(int accountAgeDays) {
        UUID member = createUser(true, false, NOW.minusSeconds(accountAgeDays * 86_400L));
        TermsAcceptanceStatus status = service.status(member);
        assertThat(status.enforcementEnabled()).isTrue();
        assertThat(status.requiresAcceptance()).isTrue();
        assertThat(status.acceptedAt()).isNull();
        assertThat(status.minimumAge()).isEqualTo(14);
        assertThat(rowCount(member)).isZero();
    }

    @Test
    @DisplayName("화면에 제시한 정확한 버전·해시와 서버 시각을 실제 PostgreSQL에 저장한다")
    void storesTheDisplayedVersionsHashesAndServerTime() {
        UUID member = createUser(true, false, NOW);
        TermsAcceptanceCommand command = currentCommand();
        TermsAcceptanceStatus result = service.accept(member, command);

        assertThat(result.requiresAcceptance()).isFalse();
        assertThat(result.acceptedAt()).isEqualTo(NOW);
        assertThat(stored(member)).isEqualTo(new TermsAcceptance(
                command.termsVersion(), command.termsContentHash(),
                command.privacyNoticeVersion(), command.privacyNoticeContentHash(), NOW));
        assertThat(jdbc.queryForObject("SELECT minimum_age_at_acceptance FROM user_terms_acceptances WHERE user_id=?",
                Integer.class, member)).isEqualTo(14);
        assertThat(rowCount(member)).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 버전의 반복 수락은 첫 수락 시각을 바꾸지 않는다")
    void repeatedAcceptancePreservesTheFirstServerTime() {
        UUID member = createUser(true, false, NOW);
        TermsAcceptanceStatus first = service.accept(member, currentCommand());
        when(clock.instant()).thenReturn(LATER);

        TermsAcceptanceStatus repeated = service.accept(member, currentCommand());

        assertThat(repeated.acceptedAt()).isEqualTo(first.acceptedAt()).isEqualTo(NOW);
        assertThat(stored(member).acceptedAt()).isEqualTo(NOW);
        assertThat(rowCount(member)).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 수락은 첫 INSERT의 커밋을 기다린 뒤 같은 최초 시각과 한 행만 반환한다")
    void concurrentAcceptanceReadsTheFirstCommittedReceipt() throws Exception {
        UUID member = createUser(true, false, NOW);
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        CountDownLatch firstUncommitted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondInsertStarted = new CountDownLatch(1);
        int[] secondPid = new int[1];
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<TermsAcceptanceStatus> first = executor.submit(() -> transaction.execute(status -> {
                TermsAcceptanceStatus accepted = service.accept(member, currentCommand());
                firstUncommitted.countDown();
                await(releaseFirst);
                return accepted;
            }));
            assertThat(firstUncommitted.await(5, TimeUnit.SECONDS)).isTrue();
            when(clock.instant()).thenReturn(LATER);
            JdbcTermsAcceptanceRepository target = AopTestUtils.getUltimateTargetObject(repository);
            doAnswer(invocation -> {
                secondPid[0] = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                secondInsertStarted.countDown();
                return invocation.callRealMethod();
            }).when(target).insert(eq(member), any(TermsAcceptance.class));

            Future<TermsAcceptanceStatus> second = executor.submit(() -> service.accept(member, currentCommand()));
            assertThat(secondInsertStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertBlockedByAnotherTransaction(secondPid[0]);
            releaseFirst.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).acceptedAt()).isEqualTo(NOW);
            assertThat(second.get(5, TimeUnit.SECONDS).acceptedAt()).isEqualTo(NOW);
            assertThat(stored(member).acceptedAt()).isEqualTo(NOW);
            assertThat(rowCount(member)).isEqualTo(1);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"termsVersion", "termsHash", "privacyVersion", "privacyHash"})
    @DisplayName("이전 버전이나 변조한 문서 해시는 수락 기록을 남기지 않는다")
    void rejectsChangedDocumentWithoutSaving(String changedField) {
        UUID member = createUser(true, false, NOW);
        TermsAcceptanceCommand valid = currentCommand();
        TermsAcceptanceCommand changed = new TermsAcceptanceCommand(
                changedField.equals("termsVersion") ? "2026-10-21" : valid.termsVersion(),
                changedField.equals("termsHash") ? "0".repeat(64) : valid.termsContentHash(),
                changedField.equals("privacyVersion") ? "2026-08-23" : valid.privacyNoticeVersion(),
                changedField.equals("privacyHash") ? "0".repeat(64) : valid.privacyNoticeContentHash(), true, true);

        assertThatThrownBy(() -> service.accept(member, changed)).isInstanceOf(LegalDocumentChangedException.class);
        assertThat(rowCount(member)).isZero();
        assertThat(service.status(member).requiresAcceptance()).isTrue();
    }

    @Test
    @DisplayName("14세 이상 확인을 하지 않은 요청은 수락 기록을 남기지 않는다")
    void rejectsAcceptanceWithoutAgeConfirmation() {
        UUID member = createUser(true, false, NOW);
        TermsAcceptanceCommand valid = currentCommand();
        var command = new TermsAcceptanceCommand(valid.termsVersion(), valid.termsContentHash(),
                valid.privacyNoticeVersion(), valid.privacyNoticeContentHash(), true, false);

        assertThatThrownBy(() -> service.accept(member, command)).isInstanceOf(InvalidTermsAcceptanceException.class);
        assertThat(rowCount(member)).isZero();
        assertThat(service.status(member).requiresAcceptance()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("탈퇴 회원과 OAuth 계정이 없는 게스트는 수락 조회·저장을 할 수 없다")
    void rejectsDeletedMembersAndGuestUsers(boolean deletedMember) {
        UUID member = createUser(deletedMember, deletedMember, NOW);

        assertThatThrownBy(() -> service.status(member)).isInstanceOf(AuthenticationRequiredException.class);
        assertThatThrownBy(() -> service.accept(member, currentCommand()))
                .isInstanceOf(AuthenticationRequiredException.class);
        assertThat(rowCount(member)).isZero();
    }

    private TermsAcceptanceCommand currentCommand() {
        var terms = catalog.current(LegalDocumentType.TERMS);
        var privacy = catalog.current(LegalDocumentType.PRIVACY);
        return new TermsAcceptanceCommand(terms.version(), terms.contentHash(),
                privacy.version(), privacy.contentHash(), true, true);
    }

    private UUID createUser(boolean oauthMember, boolean deleted, Instant createdAt) {
        UUID id = UUID.randomUUID();
        createdUsers.add(id);
        jdbc.update("""
                INSERT INTO users(id,name,nickname,profile_image_code,role,created_at,updated_at,deleted_at)
                VALUES (?,'약관동의회원',?,1,'USER',?,?,?)
                """, id, "동의" + id.toString().substring(0, 8), Timestamp.from(createdAt), Timestamp.from(createdAt),
                deleted ? Timestamp.from(NOW) : null);
        if (oauthMember) {
            jdbc.update("INSERT INTO oauth_accounts(user_id,provider,provider_subject) VALUES (?,'KAKAO',?)",
                    id, id.toString());
        }
        return id;
    }

    private long rowCount(UUID member) {
        return jdbc.queryForObject("SELECT count(*) FROM user_terms_acceptances WHERE user_id=?", Long.class, member);
    }

    private TermsAcceptance stored(UUID member) {
        return jdbc.queryForObject("""
                SELECT terms_version,terms_content_hash,privacy_notice_version,privacy_notice_content_hash,accepted_at
                FROM user_terms_acceptances WHERE user_id=?
                """, (rs, row) -> new TermsAcceptance(rs.getString("terms_version"), rs.getString("terms_content_hash"),
                rs.getString("privacy_notice_version"), rs.getString("privacy_notice_content_hash"),
                rs.getTimestamp("accepted_at").toInstant()), member);
    }

    private void assertBlockedByAnotherTransaction(int pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Boolean blocked = jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, pid);
            if (Boolean.TRUE.equals(blocked)) return;
            Thread.sleep(10);
        }
        throw new AssertionError("실제 UNIQUE 충돌의 커밋 대기를 확인하지 못했습니다.");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("약관 수락 경합 테스트 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("약관 수락 경합 테스트 중단", exception);
        }
    }
}
