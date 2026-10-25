package com.harudle.moderation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.auth.application.AccountAccessPolicy;
import com.harudle.auth.application.AccountSuspendedException;
import com.harudle.moderation.domain.ModerationChange;
import com.harudle.moderation.domain.ModerationTargetType;
import com.harudle.moderation.service.AdminModerationService;
import com.harudle.support.AdminModerationDatabaseTestSupport;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class AccountSuspensionRequestPersistenceTest extends AdminModerationDatabaseTestSupport {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountAccessPolicy accountAccess;
    @Autowired private AdminModerationService moderation;
    @Autowired private PlatformTransactionManager transactionManager;

    private UUID administrator;
    private UUID member;

    @BeforeEach
    void setUp() {
        administrator = createUser(true);
        member = createUser(false);
    }

    @AfterEach
    void cleanUp() {
        if (member != null) {
            jdbc.update("DELETE FROM moderation_events WHERE target_type='USER' AND target_id=?", member);
            jdbc.update("DELETE FROM moderation_states WHERE target_type='USER' AND target_id=?", member);
            jdbc.update("DELETE FROM users WHERE id=?", member);
        }
        if (administrator != null) {
            jdbc.update("DELETE FROM users WHERE id=?", administrator);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Bearer 요청 확인은 진행 중인 제재·해제 잠금에 기다리지 않고 커밋된 상태만 읽는다")
    void readsCommittedRestrictionWithoutWaitingForAnUncommittedChange(boolean suspendedBefore) throws Exception {
        if (suspendedBefore) {
            moderation.change(administrator, ModerationTargetType.USER, member,
                    new ModerationChange(true, 0, "기존 계정 정지"));
        }
        CountDownLatch changeUncommitted = new CountDownLatch(1);
        CountDownLatch releaseChange = new CountDownLatch(1);
        TransactionTemplate changeTransaction = new TransactionTemplate(transactionManager);
        changeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> change = executor.submit(() -> changeTransaction.executeWithoutResult(status -> {
                moderation.change(administrator, ModerationTargetType.USER, member,
                        new ModerationChange(!suspendedBefore, suspendedBefore ? 1 : 0, "다음 계정 상태"));
                changeUncommitted.countDown();
                await(releaseChange);
            }));
            assertThat(changeUncommitted.await(5, TimeUnit.SECONDS)).isTrue();

            // 제재의 사용자·상태 행 잠금이 유지된 동안 조회가 완료되어야 한다.
            Future<Boolean> request = executor.submit(this::requestRejected);
            assertThat(request.get(5, TimeUnit.SECONDS)).isEqualTo(suspendedBefore);
            assertThat(change.isDone()).isFalse();

            releaseChange.countDown();
            change.get(5, TimeUnit.SECONDS);
            assertThat(requestRejected()).isEqualTo(!suspendedBefore);
        } finally {
            releaseChange.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private boolean requestRejected() {
        try {
            accountAccess.requireAllowedForRequest(member);
            return false;
        } catch (AccountSuspendedException exception) {
            return true;
        }
    }

    private UUID createUser(boolean admin) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,name,nickname,profile_image_code,role) VALUES (?,'요청제재회원',?,1,?)",
                id, "요청" + id.toString().substring(0, 8), admin ? "ADMIN" : "USER");
        jdbc.update("INSERT INTO oauth_accounts(user_id,provider,provider_subject) VALUES (?,'KAKAO',?)",
                id, id.toString());
        return id;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("요청 조회와 제재 경합 테스트 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("요청 조회와 제재 경합 테스트 중단", exception);
        }
    }
}
