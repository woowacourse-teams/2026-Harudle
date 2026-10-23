package com.harudle.block.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.block.domain.InvalidBlockException;
import com.harudle.block.repository.BlockRepository;
import com.harudle.block.service.BlockCursorCodec;
import com.harudle.block.service.BlockInteractionPolicy;
import com.harudle.block.service.BlockService;
import com.harudle.block.service.exception.BlockTargetNotFoundException;
import com.harudle.common.testdata.DevTestDataVisibility;
import com.harudle.profile.service.port.PublicProfileReader;
import com.harudle.profile.service.port.PublicProfileReader.Profile;
import com.harudle.push.infrastructure.PostgresFeedPushPublicationGuard;
import com.harudle.support.BlockDatabaseTestSupport;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=validate", "DEPLOY_ENV="})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaBlockRepository.class, BlockService.class, BlockInteractionPolicy.class, BlockCursorCodec.class,
        PostgresFeedPushPublicationGuard.class, DevTestDataVisibility.class, BlockPersistenceTest.ClockConfiguration.class})
class BlockPersistenceTest extends BlockDatabaseTestSupport {
    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
    @Autowired private BlockService service;
    @Autowired private BlockInteractionPolicy policy;
    @Autowired private BlockRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private PublicProfileReader profiles;
    private final List<UUID> users = new ArrayList<>();
    private TransactionTemplate transactions;
    private UUID actor;
    private UUID target;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        transactions.setTimeout(10);
        actor = member(null);
        target = member(null);
        when(profiles.readAll(any())).thenAnswer(invocation -> {
            Set<UUID> ids = invocation.getArgument(0);
            return ids.stream().collect(Collectors.toMap(id -> id, this::profile));
        });
    }

    @AfterEach
    void tearDown() {
        users.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
    }

    @Test
    @DisplayName("중복 차단은 최초 시각과 ID를 보존하고 상대 방향은 별도 행으로 저장한다")
    void preservesOriginalDirectionAndFirstTimestamp() {
        service.block(actor, target);
        UUID id = jdbc.queryForObject("SELECT id FROM user_blocks WHERE blocker_id = ?", UUID.class, actor);
        service.block(actor, target);
        service.block(target, actor);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_blocks WHERE blocker_id IN (?, ?)",
                Long.class, actor, target)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT id FROM user_blocks WHERE blocker_id = ?", UUID.class, actor))
                .isEqualTo(id);
        assertThat(service.list(actor, null, 20).items()).singleElement().satisfies(row -> {
            assertThat(row.userId()).isEqualTo(target);
            assertThat(row.blockedAt()).isEqualTo(NOW);
        });
        List<UUID> blockedIds = transactions.execute(status -> entityManager.createQuery(
                "SELECT b.blockedId FROM UserBlock b WHERE b.blockerId = :actor", UUID.class)
                .setParameter("actor", actor).getResultList());
        assertThat(blockedIds).containsExactly(target);
    }

    @Test
    @DisplayName("대상이 탈퇴해도 해제할 수 있고 상대 방향과 최초 목록 시각은 바꾸지 않는다")
    void unblocksInactiveTargetWithoutRemovingOppositeDirection() {
        service.block(actor, target);
        service.block(target, actor);
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", target);
        assertThat(service.list(actor, null, 20).items()).singleElement().satisfies(row -> {
            assertThat(row.nickname()).isEqualTo("탈퇴한 사용자");
            assertThat(row.profileImageUrl()).isNull();
        });
        service.unblock(actor, target);
        service.unblock(actor, target);
        assertThat(service.list(actor, null, 20).items()).isEmpty();
        Boolean blocked = transactions.execute(status -> repository.existsBetween(actor, target));
        assertThat(blocked).isTrue();
    }

    @Test
    @DisplayName("게스트·탈퇴자·없는 회원과 자기 차단은 저장하지 않는다")
    void validatesActiveMembersAndRejectsSelf() {
        UUID guest = user(null);
        assertThatThrownBy(() -> service.block(guest, target)).isInstanceOf(AuthenticationRequiredException.class);
        assertThatThrownBy(() -> service.block(actor, guest)).isInstanceOf(BlockTargetNotFoundException.class);
        assertThatThrownBy(() -> service.block(actor, UUID.randomUUID()))
                .isInstanceOf(BlockTargetNotFoundException.class);
        assertThatThrownBy(() -> service.block(actor, actor)).isInstanceOf(InvalidBlockException.class);
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", target);
        assertThatThrownBy(() -> service.block(actor, target)).isInstanceOf(BlockTargetNotFoundException.class);
        assertThat(service.list(actor, null, 20).items()).isEmpty();
    }

    @Test
    @DisplayName("dev 테스트 계정은 서로 같은 계정군의 대상만 차단할 수 있다")
    void separatesDevTestTargets() {
        UUID testActor = member("actor.loadtest.invalid");
        UUID testTarget = member("target.loadtest.invalid");
        var devRepository = new JpaBlockRepository(entityManager, new DevTestDataVisibility("dev"));
        assertThat(devRepository.isActiveTarget(actor, testTarget)).isFalse();
        assertThat(devRepository.isActiveTarget(testActor, target)).isFalse();
        assertThat(devRepository.isActiveTarget(testActor, testTarget)).isTrue();
        assertThat(devRepository.isActiveTarget(actor, target)).isTrue();
        assertThat(repository.isActiveTarget(actor, testTarget)).isTrue();
    }

    @Test
    @DisplayName("동일 시각 경계의 차단을 해제해도 본인 목록의 다음 페이지를 중복 없이 읽는다")
    void pagesAfterDeletedBoundaryWithoutLeakingIncomingBlocks() {
        UUID second = member(null);
        UUID third = member(null);
        service.block(actor, target);
        service.block(actor, second);
        service.block(third, actor);
        var page = service.list(actor, null, 1);
        assertThat(page.hasNext()).isTrue();
        UUID boundary = page.items().getFirst().userId();
        service.unblock(actor, boundary);
        var next = service.list(actor, page.nextCursor(), 1);
        assertThat(next.items()).singleElement().satisfies(row -> assertThat(row.userId()).isNotEqualTo(boundary));
        assertThat(next.hasNext()).isFalse();
        assertThat(next.items()).extracting(row -> row.userId()).doesNotContain(third);
    }

    @Test
    @DisplayName("차단은 양방향 미발송 작업만 취소하고 보낸 작업과 무관한 회원 작업은 유지한다")
    void cancelsPendingAndProcessingPermanentlyAfterUnblock() {
        UUID other = member(null);
        UUID pending = job(actor, target, "PENDING");
        UUID processing = job(target, actor, "PROCESSING");
        UUID sent = job(actor, target, "SENT");
        UUID unrelated = job(other, actor, "PENDING");
        service.block(actor, target);
        assertThat(jobStatus(pending)).isEqualTo("CANCELLED");
        assertThat(jobStatus(processing)).isEqualTo("CANCELLED");
        assertThat(jobStatus(sent)).isEqualTo("SENT");
        assertThat(jobStatus(unrelated)).isEqualTo("PENDING");
        service.unblock(actor, target);
        assertThat(jobStatus(pending)).isEqualTo("CANCELLED");
        assertThat(jobStatus(processing)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("후속 실패는 차단과 기존 작업 취소를 같은 트랜잭션에서 함께 롤백한다")
    void rollsBackBlockAndCancellationTogether() {
        UUID pending = job(actor, target, "PENDING");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            service.block(actor, target);
            throw new IllegalStateException("의도한 테스트 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(service.list(actor, null, 20).items()).isEmpty();
        assertThat(jobStatus(pending)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("같은 쌍의 반대 방향 차단은 기존 접촉 잠금 종료를 기다린 뒤 새 상태를 읽는다")
    void serializesReversedPairWithConcurrentInteraction() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger writerPid = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var interaction = executor.submit(() -> transactions.execute(status -> {
                boolean blocked = policy.lockAndIsBlocked(actor, target);
                locked.countDown();
                await(release);
                return blocked;
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var block = executor.submit(() -> transactions.executeWithoutResult(status -> {
                writerPid.set(Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                service.block(target, actor);
            }));
            awaitAdvisoryWait(writerPid);
            assertThat(block.isDone()).isFalse();
            release.countDown();
            assertThat(interaction.get(10, TimeUnit.SECONDS)).isFalse();
            block.get(10, TimeUnit.SECONDS);
            Boolean blocked = transactions.execute(status -> policy.lockAndIsBlocked(actor, target));
            assertThat(blocked).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("정책 잠금은 트랜잭션을 요구하고 DB는 자기 차단을 거절하며 양쪽 회원 삭제에 정리한다")
    void enforcesTransactionAndDatabaseConstraints() {
        assertThatThrownBy(() -> policy.lockAndIsBlocked(actor, target))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_blocks (id, blocker_id, blocked_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), actor, actor)).isInstanceOf(DataIntegrityViolationException.class);
        service.block(actor, target);
        service.block(target, actor);
        jdbc.update("DELETE FROM users WHERE id = ?", target);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_blocks WHERE blocker_id = ? OR blocked_id = ?",
                Long.class, actor, actor)).isZero();
    }

    private Profile profile(UUID userId) {
        Profile profile = mock(Profile.class);
        when(profile.id()).thenReturn(userId);
        when(profile.nickname()).thenReturn("차단 회원");
        when(profile.profileImageUrl()).thenReturn(URI.create("https://example.com/profile.svg"));
        return profile;
    }

    private UUID user(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, primary_email, name, nickname, profile_image_code)
                VALUES (?, ?, '차단통합회원', ?, 1)
                """, id, email, "차단" + id.toString().substring(0, 8));
        users.add(id);
        return id;
    }

    private UUID member(String email) {
        UUID id = user(email);
        jdbc.update("INSERT INTO oauth_accounts (user_id, provider, provider_subject) VALUES (?, 'KAKAO', ?)",
                id, id.toString());
        return id;
    }

    private UUID job(UUID author, UUID recipient, String status) {
        UUID diary = UUID.randomUUID();
        UUID feed = UUID.randomUUID();
        UUID registration = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, CURRENT_DATE, '테스트')",
                diary, author);
        jdbc.update("INSERT INTO feeds (id, diary_id, category_id) VALUES (?, ?, "
                + "(SELECT id FROM categories WHERE name = '일상'))", feed, diary);
        jdbc.update("INSERT INTO push_registrations (id, user_id, device_id, recipient_value) VALUES (?, ?, ?, ?)",
                registration, recipient, UUID.randomUUID(), UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO notification_outbox (id, event_id, feed_id, recipient_user_id, push_registration_id,
                    status, lock_token, locked_until, sent_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, UUID.randomUUID(), feed, recipient, registration, status,
                "PROCESSING".equals(status) ? UUID.randomUUID() : null,
                "PROCESSING".equals(status) ? Timestamp.from(NOW.plusSeconds(300)) : null,
                "SENT".equals(status) ? Timestamp.from(NOW) : null);
        return id;
    }

    private String jobStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM notification_outbox WHERE id = ?", String.class, id);
    }

    private void awaitAdvisoryWait(AtomicInteger pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Long count = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE pid = ? AND wait_event_type = 'Lock' AND wait_event = 'advisory'", Long.class, pid.get());
            if (Long.valueOf(1).equals(count)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("회원 쌍 잠금 대기를 확인하지 못했습니다.");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("잠금 해제 신호를 기다리지 못했습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("잠금 테스트가 중단됐습니다.");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean
        Clock serviceClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
