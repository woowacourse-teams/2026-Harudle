package com.harudle.admin.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.harudle.auth.application.AccountAccessPolicy;
import com.harudle.auth.application.AccountSuspendedException;
import com.harudle.auth.application.IssuedRefreshToken;
import com.harudle.auth.application.RefreshTokenService;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.token.RefreshTokenHasher;
import com.harudle.moderation.domain.ModerationChange;
import com.harudle.moderation.domain.ModerationTargetType;
import com.harudle.moderation.infrastructure.JpaModerationRepository;
import com.harudle.moderation.service.AdminModerationService;
import com.harudle.support.AdminModerationDatabaseTestSupport;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties = "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ")
class AdminModerationRefreshPersistenceTest extends AdminModerationDatabaseTestSupport {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private RefreshTokenService refreshTokens;
    @Autowired private RefreshTokenHasher tokenHasher;
    @Autowired private AccountAccessPolicy accountAccess;
    @Autowired private AdminModerationService moderation;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private JpaModerationRepository restrictions;

    private UUID administrator;
    private User member;

    @BeforeEach
    void setUp() {
        administrator = createUser(true);
        member = users.findById(createUser(false)).orElseThrow();
    }

    @AfterEach
    void cleanUp() {
        if (member != null) {
            jdbc.update("DELETE FROM moderation_events WHERE target_type='USER' AND target_id=?", member.getId());
            jdbc.update("DELETE FROM moderation_states WHERE target_type='USER' AND target_id=?", member.getId());
            jdbc.update("DELETE FROM users WHERE id=?", member.getId());
        }
        if (administrator != null) {
            jdbc.update("DELETE FROM users WHERE id=?", administrator);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"issue", "rotate"})
    @DisplayName("발급의 공유 사용자 gate가 끝날 때까지 정지는 대기하고 새로 발급한 토큰까지 모두 폐기한다")
    void waitsForInFlightTokenIssuanceAndRevokesItsNewToken(String operation) throws Exception {
        assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("repeatable read");
        IssuedRefreshToken original = refreshTokens.issue(member, Instant.now());
        CountDownLatch userGateHeld = new CountDownLatch(1);
        CountDownLatch releaseIssuance = new CountDownLatch(1);
        CountDownLatch banStarted = new CountDownLatch(1);
        AtomicBoolean holdFirstCheck = new AtomicBoolean(true);
        AtomicBoolean captureFirstBan = new AtomicBoolean(true);
        int[] banPid = new int[1];
        JpaModerationRepository target = AopTestUtils.getUltimateTargetObject(restrictions);
        doAnswer(invocation -> {
            Object suspended = invocation.callRealMethod();
            if (member.getId().equals(invocation.getArgument(0)) && holdFirstCheck.compareAndSet(true, false)) {
                assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
                userGateHeld.countDown();
                await(releaseIssuance);
            }
            return suspended;
        }).when(target).isSuspended(any(UUID.class));
        doAnswer(invocation -> {
            if (administrator.equals(invocation.getArgument(0)) && captureFirstBan.compareAndSet(true, false)) {
                banPid[0] = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                banStarted.countDown();
            }
            return invocation.callRealMethod();
        }).when(target).isActiveAdmin(any(UUID.class));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<IssuedRefreshToken> issuance = executor.submit(() -> operation.equals("rotate")
                    ? refreshTokens.rotate(original.rawToken(), Instant.now()).issuedRefreshToken()
                    : refreshTokens.issue(member, Instant.now()));
            assertThat(userGateHeld.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> ban = executor.submit(() -> moderation.change(administrator,
                    ModerationTargetType.USER, member.getId(), new ModerationChange(true, 0, "계정 정지")));
            assertThat(banStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertBlockedByAnotherTransaction(banPid[0]);
            releaseIssuance.countDown();
            IssuedRefreshToken issued = issuance.get(5, TimeUnit.SECONDS);
            ban.get(5, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id=?",
                    Integer.class, member.getId())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id=? AND revoked_at IS NULL",
                    Integer.class, member.getId())).isZero();
            assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM refresh_tokens WHERE token_hash=?",
                    Boolean.class, tokenHasher.hash(issued.rawToken()))).isTrue();
            assertThatThrownBy(() -> accountAccess.requireAllowed(member.getId()))
                    .isInstanceOf(AccountSuspendedException.class);
        } finally {
            releaseIssuance.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("정지가 사용자 gate를 먼저 잡으면 refresh는 토큰 잠금 전에 기다리고 커밋 뒤 새 정책으로 거절된다")
    void deniesRefreshAfterWaitingForAnEarlierSuspension() throws Exception {
        IssuedRefreshToken original = refreshTokens.issue(member, Instant.now());
        CountDownLatch banUncommitted = new CountDownLatch(1);
        CountDownLatch releaseBan = new CountDownLatch(1);
        CountDownLatch refreshStarted = new CountDownLatch(1);
        AtomicBoolean captureFirstRefresh = new AtomicBoolean(true);
        int[] refreshPid = new int[1];
        JpaModerationRepository target = AopTestUtils.getUltimateTargetObject(restrictions);
        doAnswer(invocation -> {
            if (member.getId().equals(invocation.getArgument(0)) && captureFirstRefresh.compareAndSet(true, false)) {
                assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
                refreshPid[0] = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                refreshStarted.countDown();
            }
            return invocation.callRealMethod();
        }).when(target).isSuspended(any(UUID.class));
        var banTransaction = new TransactionTemplate(transactionManager);
        banTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> ban = executor.submit(() -> banTransaction.executeWithoutResult(status -> {
                moderation.change(administrator, ModerationTargetType.USER, member.getId(),
                        new ModerationChange(true, 0, "먼저 확정할 정지"));
                banUncommitted.countDown();
                await(releaseBan);
            }));
            assertThat(banUncommitted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> refresh = executor.submit(() -> {
                try {
                    refreshTokens.rotate(original.rawToken(), Instant.now());
                    return false;
                } catch (AccountSuspendedException exception) {
                    return true;
                }
            });
            assertThat(refreshStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertBlockedByAnotherTransaction(refreshPid[0]);
            releaseBan.countDown();
            ban.get(5, TimeUnit.SECONDS);
            assertThat(refresh.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id=?",
                    Integer.class, member.getId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id=? AND revoked_at IS NULL",
                    Integer.class, member.getId())).isZero();
        } finally {
            releaseBan.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private UUID createUser(boolean admin) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,name,nickname,profile_image_code,role) VALUES (?,'발급경합회원',?,1,?)",
                id, "발급" + id.toString().substring(0, 8), admin ? "ADMIN" : "USER");
        jdbc.update("INSERT INTO oauth_accounts(user_id,provider,provider_subject) VALUES (?,'KAKAO',?)",
                id, id.toString());
        return id;
    }

    private void assertBlockedByAnotherTransaction(int pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Boolean blocked = jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, pid);
            if (Boolean.TRUE.equals(blocked)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("실제 사용자 gate 잠금 대기를 확인하지 못했습니다.");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("발급과 제재 경합 테스트 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("발급과 제재 경합 테스트 중단");
        }
    }
}
