package com.harudle.push.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.support.ServiceIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcPushDeliveryQueueTest extends ServiceIntegrationTestSupport {

    @Autowired private JdbcPushDeliveryQueue queue;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    private final UUID author = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private final UUID registration = UUID.randomUUID();
    private final UUID feed = UUID.randomUUID();
    private final UUID job = UUID.randomUUID();

    // 큐가 비교하는 시각은 모두 이 기준으로 저장한다. DB 기본값(CURRENT_TIMESTAMP)을 쓰면
    // DB와 JVM 시계 차이만큼 작업이 아직 대기 중인 것으로 보일 수 있다.
    // PostgreSQL은 마이크로초까지 저장하므로 반올림으로 기준보다 늦어지지 않게 자른다.
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("INSERT INTO users (id, name) VALUES (?, '작성자'), (?, '수신자')", author, recipient);
        UUID diary = UUID.randomUUID();
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, CURRENT_DATE, '일기')",
                diary, author);
        jdbc.update("INSERT INTO feeds (id, diary_id, category_id, published_at) VALUES (?, ?, "
                + "(SELECT id FROM categories WHERE name = '일상'), ?)", feed, diary, time(now));
        jdbc.update("INSERT INTO auth_sessions (id, user_id) VALUES (?, ?)", session, recipient);
        jdbc.update("""
                INSERT INTO refresh_tokens (user_id, token_hash, expires_at, session_id)
                VALUES (?, ?, ?, ?)
                """, recipient, UUID.randomUUID().toString().replace("-", "").repeat(2),
                time(now.plus(Duration.ofDays(1))), session);
        jdbc.update("""
                INSERT INTO push_registrations (id, user_id, device_id, recipient_value, session_id)
                VALUES (?, ?, ?, 'F1', ?)
                """, registration, recipient, UUID.randomUUID(), session);
        jdbc.update("""
                INSERT INTO notification_outbox
                    (id, event_id, feed_id, recipient_user_id, push_registration_id, available_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, job, UUID.randomUUID(), feed, recipient, registration, time(now));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", author, recipient);
    }

    @Test
    @DisplayName("한 워커가 점유한 작업은 다른 워커가 가져가지 못하고 점유 만료 뒤에는 다시 가져간다")
    void reclaimsOnlyAfterLeaseExpires() {
        JdbcPushDeliveryQueue.Claim first = queue.claim(now).orElseThrow();

        assertThat(queue.claim(now.plusSeconds(1))).isEmpty();
        JdbcPushDeliveryQueue.Claim second = queue.claim(now.plus(Duration.ofMinutes(6))).orElseThrow();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.lockToken()).isNotEqualTo(first.lockToken());
        assertThat(second.attemptCount()).isEqualTo(2);
        assertThat(queue.sent(first, now.plus(Duration.ofMinutes(6)))).isFalse();
        assertThat(queue.sent(second, now.plus(Duration.ofMinutes(6)))).isTrue();
        assertThat(status()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("다른 워커가 DB에서 잠근 작업은 기다리지 않고 건너뛴다")
    void skipsRowLockedByAnotherWorker() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM notification_outbox WHERE id = ? FOR UPDATE", UUID.class, job);
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("잠금 해제 시간 초과");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(queue.claim(now)).isEmpty();
            } finally {
                release.countDown();
            }
            holder.get(5, TimeUnit.SECONDS);
        }
        assertThat(queue.claim(now)).isPresent();
    }

    @Test
    @DisplayName("같은 피드 행이 잠겨 있어도 기기별 발송 작업은 점유할 수 있다")
    void claimsOutboxWithoutLockingFeed() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM feeds WHERE id = ? FOR UPDATE", UUID.class, feed);
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("잠금 해제 시간 초과");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(queue.claim(now)).isPresent();
            } finally {
                release.countDown();
            }
            holder.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("전송 직전에는 현재 FID를 다시 읽고 해제된 등록은 발송 대상에서 제외한다")
    void readsLatestFidAndSkipsRevokedRegistration() {
        JdbcPushDeliveryQueue.Claim claim = queue.claim(now).orElseThrow();
        jdbc.update("UPDATE push_registrations SET recipient_value = 'F2', version = 1 WHERE id = ?", registration);

        assertThat(queue.target(claim, now)).contains(new JdbcPushDeliveryQueue.Target("F2", 1));

        jdbc.update("UPDATE push_registrations SET revoked_at = CURRENT_TIMESTAMP WHERE id = ?", registration);
        assertThat(queue.target(claim, now)).isEmpty();
        assertThat(queue.cancel(claim, now)).isTrue();
        assertThat(queue.sent(claim, now)).isFalse();
        assertThat(status()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("UNREGISTERED 응답 뒤 FID가 그대로면 등록을 해제하고 미발송 작업을 취소한다")
    void revokesOnlyTheFidThatWasSent() {
        JdbcPushDeliveryQueue.Claim claim = queue.claim(now).orElseThrow();
        JdbcPushDeliveryQueue.Target sentTo = queue.target(claim, now).orElseThrow();
        UUID waiting = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notification_outbox
                    (id, event_id, feed_id, recipient_user_id, push_registration_id)
                VALUES (?, ?, ?, ?, ?)
                """, waiting, UUID.randomUUID(), feed, recipient, registration);

        assertThat(queue.revokeUnregistered(claim, sentTo, now)).isTrue();
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM push_registrations WHERE id = ?",
                Boolean.class, registration)).isTrue();
        assertThat(status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE id = ?", String.class, waiting))
                .isEqualTo("CANCELLED");
        assertThat(queue.sent(claim, now)).isFalse();
    }

    @Test
    @DisplayName("전송 중 FID가 바뀌면 옛 FID의 UNREGISTERED 응답으로 새 등록을 해제하지 않는다")
    void preservesChangedFidAfterUnregistered() {
        JdbcPushDeliveryQueue.Claim claim = queue.claim(now).orElseThrow();
        JdbcPushDeliveryQueue.Target sentTo = queue.target(claim, now).orElseThrow();
        jdbc.update("UPDATE push_registrations SET recipient_value = 'F2', version = 1 WHERE id = ?", registration);

        assertThat(queue.revokeUnregistered(claim, sentTo, now)).isFalse();
        assertThat(queue.target(claim, now)).contains(new JdbcPushDeliveryQueue.Target("F2", 1));
        assertThat(status()).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("세션이나 피드가 종료되면 전송 직전 검사에서 발송 대상을 찾지 못한다")
    void skipsInvalidSessionAndDeletedFeed() {
        JdbcPushDeliveryQueue.Claim claim = queue.claim(now).orElseThrow();
        jdbc.update("UPDATE auth_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE id = ?", session);
        assertThat(queue.target(claim, now)).isEmpty();
        jdbc.update("UPDATE auth_sessions SET revoked_at = NULL WHERE id = ?", session);
        jdbc.update("UPDATE feeds SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", feed);
        assertThat(queue.target(claim, now)).isEmpty();
    }

    @Test
    @DisplayName("갱신 토큰이 만료되거나 회원이 탈퇴하면 발송 직전에 대상을 제외한다")
    void skipsExpiredTokenAndDeletedUser() {
        JdbcPushDeliveryQueue.Claim claim = queue.claim(now).orElseThrow();
        jdbc.update("UPDATE refresh_tokens SET expires_at = ? WHERE session_id = ?",
                time(now.minus(Duration.ofMinutes(1))), session);
        assertThat(queue.target(claim, now)).isEmpty();

        jdbc.update("UPDATE refresh_tokens SET expires_at = ? WHERE session_id = ?",
                time(now.plus(Duration.ofDays(1))), session);
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", recipient);
        assertThat(queue.target(claim, now)).isEmpty();
    }

    @Test
    @DisplayName("재시도 예정 시각 전에는 다시 점유하지 않고 이전 워커는 결과를 바꾸지 못한다")
    void retriesAtScheduledTimeWithNewLock() {
        JdbcPushDeliveryQueue.Claim first = queue.claim(now).orElseThrow();
        Instant later = now.plusSeconds(30);

        assertThat(queue.retry(first, later, "UNAVAILABLE", now)).isTrue();
        assertThat(queue.claim(later.minusMillis(1))).isEmpty();
        JdbcPushDeliveryQueue.Claim second = queue.claim(later).orElseThrow();
        assertThat(queue.fail(first, "OLD_RESULT", later)).isFalse();
        assertThat(queue.fail(second, "INVALID_ARGUMENT", later)).isTrue();
        assertThat(status()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("24시간이 지난 미발송 작업은 실패로 끝내고 다시 점유하지 않는다")
    void expiresOldJob() {
        jdbc.update("UPDATE feeds SET published_at = ? WHERE id = ?", time(now.minus(Duration.ofHours(25))), feed);

        assertThat(queue.expire(now)).isEqualTo(1);
        assertThat(queue.claim(now)).isEmpty();
        assertThat(status()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("24시간이 지나도 다른 워커가 점유 중이면 점유 만료까지 기다린다")
    void waitsForActiveLeaseBeforeExpiring() {
        jdbc.update("UPDATE feeds SET published_at = ? WHERE id = ?", time(now.minus(Duration.ofHours(25))), feed);
        jdbc.update("""
                UPDATE notification_outbox SET status = 'PROCESSING', lock_token = ?, locked_until = ?
                WHERE id = ?
                """, UUID.randomUUID(), time(now.plus(Duration.ofMinutes(5))), job);

        assertThat(queue.expire(now)).isZero();
        assertThat(status()).isEqualTo("PROCESSING");
        assertThat(queue.expire(now.plus(Duration.ofMinutes(6)))).isEqualTo(1);
        assertThat(status()).isEqualTo("FAILED");
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM notification_outbox WHERE id = ?", String.class, job);
    }

    private static Timestamp time(Instant instant) {
        return Timestamp.from(instant);
    }
}
