package com.harudle.block.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.block.service.BlockInteractionPolicy;
import com.harudle.common.persistence.PostgresTransactionLockTimeout;
import com.harudle.common.testdata.DevTestDataVisibility;
import com.harudle.push.infrastructure.PostgresFeedPushPublicationGuard;
import com.harudle.support.BlockDatabaseTestSupport;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=validate", "DEPLOY_ENV=",
        "harudle.database.interaction-lock-timeout=100ms"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({PostgresTransactionLockTimeout.class, JpaBlockRepository.class, BlockInteractionPolicy.class,
        PostgresFeedPushPublicationGuard.class, DevTestDataVisibility.class})
class BlockLockTimeoutPersistenceTest extends BlockDatabaseTestSupport {
    private static final long FREE_LOCK = 8_307_624_183_173L;
    @Autowired private PostgresTransactionLockTimeout timeout;
    @Autowired private BlockInteractionPolicy pairs;
    @Autowired private PostgresFeedPushPublicationGuard publications;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("제한은 현재 트랜잭션에만 적용하며 기존의 더 짧은 설정을 늘리지 않는다")
    void preservesStricterTimeoutAndRestoresConnectionSetting() {
        var transactions = transactions();
        String original = transactions.execute(status -> currentTimeout());
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('lock_timeout', '20ms', true)", String.class);
            timeout.apply();
            assertThat(currentTimeout()).isEqualTo("20ms");
        });
        String afterStricterTransaction = transactions.execute(status -> currentTimeout());
        assertThat(afterStricterTransaction).isEqualTo(original);
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('lock_timeout', '0', true)", String.class);
            timeout.apply();
            assertThat(currentTimeout()).isEqualTo("100ms");
        });
        String afterLimitedTransaction = transactions.execute(status -> currentTimeout());
        assertThat(afterLimitedTransaction).isEqualTo(original);
        assertThatThrownBy(timeout::apply).isInstanceOf(IllegalTransactionStateException.class);
    }

    @ParameterizedTest
    @EnumSource(LockKind.class)
    @DisplayName("사용자 쌍과 게시 및 피드 행 잠금은 제한 시간에 실패하고 변경과 선행 잠금을 함께 되돌린다")
    void timesOutAndRollsBackWithoutWaitingForHolder(LockKind kind) throws Exception {
        UUID author = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID row = UUID.randomUUID();
        UUID probe = UUID.randomUUID();
        insertUser(row);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var holder = executor.submit(() -> transactions().executeWithoutResult(status -> {
                hold(kind, author, other, row);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var waiter = executor.submit(() -> waitForLock(kind, author, other, row, probe));
            RuntimeException failure = waiter.get(5, TimeUnit.SECONDS);
            assertThat(failure).isNotNull();
            assertThat(sqlState(failure)).isEqualTo("55P03");
            assertThat(holder.isDone()).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Long.class, probe)).isZero();
            transactions().executeWithoutResult(status -> {
                assertThat(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, FREE_LOCK))
                        .isTrue();
                timeout.apply();
                if (kind == LockKind.PUBLICATION_EXCLUSIVE) {
                    publications.lockForPublication(other);
                }
            });
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            jdbc.update("DELETE FROM users WHERE id IN (?, ?)", row, probe);
        }
    }

    private RuntimeException waitForLock(LockKind kind, UUID author, UUID other, UUID row, UUID probe) {
        try {
            transactions().executeWithoutResult(status -> {
                timeout.apply();
                insertUser(probe);
                jdbc.execute("SELECT pg_advisory_xact_lock(" + FREE_LOCK + ")");
                waitOn(kind, author, other, row);
            });
            return null;
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private void hold(LockKind kind, UUID author, UUID other, UUID row) {
        switch (kind) {
            case PAIR -> pairs.lockPair(author, other);
            case PUBLICATION_EXCLUSIVE -> publications.lockForPublication(author);
            case PUBLICATION_SHARED -> publications.lockForBlock(author, other);
            case ROW -> lockRow(row);
        }
    }

    private void waitOn(LockKind kind, UUID author, UUID other, UUID row) {
        switch (kind) {
            case PAIR -> pairs.lockPair(author, other);
            case PUBLICATION_EXCLUSIVE -> publications.lockForBlock(author, other);
            case PUBLICATION_SHARED -> publications.lockForPublication(author);
            case ROW -> lockRow(row);
        }
    }

    private void lockRow(UUID row) {
        jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, row);
    }

    private void insertUser(UUID id) {
        jdbc.update("INSERT INTO users (id, name, nickname, profile_image_code) VALUES (?, '대기 검증', ?, 1)",
                id, "잠금" + id.toString().substring(0, 8));
    }

    private String currentTimeout() {
        return jdbc.queryForObject("SELECT current_setting('lock_timeout')", String.class);
    }

    private TransactionTemplate transactions() {
        var transactions = new TransactionTemplate(transactionManager);
        transactions.setIsolationLevelName("ISOLATION_READ_COMMITTED");
        transactions.setTimeout(10);
        return transactions;
    }

    private static String sqlState(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("잠금 보유 트랜잭션 대기 시간을 초과했습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    enum LockKind { PAIR, PUBLICATION_EXCLUSIVE, PUBLICATION_SHARED, ROW }
}
