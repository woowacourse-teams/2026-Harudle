package com.harudle.push.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.feed.service.FeedDeletionService;
import com.harudle.push.service.port.FeedPushOutbox;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class JpaFeedPushOutboxTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transactions;
    @Autowired private FeedPushOutbox outbox;
    @Autowired private FeedDeletionService deletion;
    @MockitoBean private JwtDecoder jwtDecoder;

    private final UUID author = UUID.randomUUID();
    private final UUID reader = UUID.randomUUID();
    private final UUID deletedUser = UUID.randomUUID();
    private final UUID feed = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        update("INSERT INTO users (id, name) VALUES (?, '작성자'), (?, '독자'), (?, '탈퇴자')",
                author, reader, deletedUser);
        UUID diary = UUID.randomUUID();
        update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, CURRENT_DATE, '일기')",
                diary, author);
        update("INSERT INTO feeds (id, diary_id, category_id) VALUES (?, ?, "
                + "(SELECT id FROM categories WHERE name = '일상'))", feed, diary);
    }

    @AfterEach
    void tearDown() {
        update("DELETE FROM users WHERE id IN (?, ?, ?)", author, reader, deletedUser);
    }

    @Test
    @DisplayName("피드 게시 시 작성자와 유효 토큰이 없는 세션은 제외하고, 같은 이벤트는 기기별로 한 번만 예약한다")
    void reservesOncePerEligibleDeviceAndSkipsAuthorOrInvalidSession() {
        registration(author, true);
        registration(reader, true);
        registration(reader, true);
        registration(reader, false);
        FeedPushOutbox.FeedPublished event = event();

        int created = transactions.execute(status -> outbox.enqueuePublished(event));
        int duplicate = transactions.execute(status -> outbox.enqueuePublished(event));
        assertThat(created).isEqualTo(2);
        assertThat(duplicate).isZero();
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE event_id = ?", event.eventId()))
                .isEqualTo(2);
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE recipient_user_id = ?", author))
                .isZero();
    }

    @Test
    @DisplayName("게시 시 해제된 등록·종료된 세션·만료 또는 폐기된 토큰·탈퇴 사용자·세션 없는 과거 등록은 예약하지 않는다")
    void reservesOnlyActiveRegistrationWithActiveSessionAndToken() {
        UUID active = registration(reader, true);
        UUID revokedRegistration = registration(reader, true);
        update("UPDATE push_registrations SET revoked_at = CURRENT_TIMESTAMP WHERE id = ?", revokedRegistration);

        UUID revokedSession = registration(reader, true);
        update("""
                UPDATE auth_sessions SET revoked_at = CURRENT_TIMESTAMP
                WHERE id = (SELECT session_id FROM push_registrations WHERE id = ?)
                """, revokedSession);

        UUID expiredToken = registration(reader, true);
        update("""
                UPDATE refresh_tokens SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                WHERE session_id = (SELECT session_id FROM push_registrations WHERE id = ?)
                """, expiredToken);

        UUID revokedToken = registration(reader, true);
        update("""
                UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP
                WHERE session_id = (SELECT session_id FROM push_registrations WHERE id = ?)
                """, revokedToken);

        registration(deletedUser, true);
        update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deletedUser);
        update("""
                INSERT INTO push_registrations (id, user_id, device_id, recipient_value)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), reader, UUID.randomUUID(), UUID.randomUUID().toString());

        FeedPushOutbox.FeedPublished event = event();
        int created = transactions.execute(status -> outbox.enqueuePublished(event));
        assertThat(created).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE event_id = ? AND push_registration_id = ?",
                event.eventId(), active)).isEqualTo(1);
    }

    @Test
    @DisplayName("작성자에게만 활성 등록이 있으면 발송 작업 0건으로 게시 예약이 끝난다")
    void succeedsWithNoEligibleRecipient() {
        registration(author, true);
        FeedPushOutbox.FeedPublished event = event();

        int created = transactions.execute(status -> outbox.enqueuePublished(event));
        assertThat(created).isZero();
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE event_id = ?", event.eventId()))
                .isZero();
    }

    @Test
    @DisplayName("발송 작업을 취소하면 PENDING과 PROCESSING만 취소하고 SENT는 유지한다")
    void cancelsPendingAndProcessingWithoutChangingSentJobs() {
        registration(reader, true);
        registration(reader, true);
        registration(reader, true);
        transactions.executeWithoutResult(status -> outbox.enqueuePublished(event()));
        update("""
                UPDATE notification_outbox SET status = 'PROCESSING', lock_token = gen_random_uuid(),
                    locked_until = CURRENT_TIMESTAMP + INTERVAL '1 minute'
                WHERE id = (SELECT id FROM notification_outbox WHERE feed_id = ? ORDER BY id LIMIT 1)
                """, feed);
        update("""
                UPDATE notification_outbox SET status = 'SENT', sent_at = CURRENT_TIMESTAMP
                WHERE id = (SELECT id FROM notification_outbox WHERE feed_id = ? AND status = 'PENDING'
                            ORDER BY id LIMIT 1)
                """, feed);

        int cancelled = transactions.execute(status -> outbox.cancelUnsentByFeed(feed));
        int duplicate = transactions.execute(status -> outbox.cancelUnsentByFeed(feed));
        assertThat(cancelled).isEqualTo(2);
        assertThat(duplicate).isZero();
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE feed_id = ? AND status = 'CANCELLED'", feed))
                .isEqualTo(2);
        assertThat(number("""
                SELECT count(*) FROM notification_outbox WHERE feed_id = ? AND status = 'CANCELLED'
                  AND lock_token IS NULL AND locked_until IS NULL AND sent_at IS NULL
                """, feed)).isEqualTo(2);
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE feed_id = ? AND status = 'SENT'", feed))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("피드를 삭제하면 같은 트랜잭션에서 아직 보내지 않은 기기별 작업을 취소한다")
    void deletingFeedCancelsReservedJobs() {
        registration(reader, true);
        transactions.executeWithoutResult(status -> outbox.enqueuePublished(event()));

        deletion.delete(author, feed);

        assertThat(number("SELECT count(*) FROM feeds WHERE id = ? AND deleted_at IS NOT NULL", feed))
                .isEqualTo(1);
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE feed_id = ? AND status = 'CANCELLED'", feed))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("발송 예약은 호출한 트랜잭션과 함께 롤백되며 트랜잭션 밖에서는 실행할 수 없다")
    void reservationRollsBackWithCallerTransaction() {
        registration(reader, true);
        FeedPushOutbox.FeedPublished event = event();
        transactions.executeWithoutResult(status -> {
            assertThat(outbox.enqueuePublished(event)).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE event_id = ?", event.eventId()))
                .isZero();
        assertThatThrownBy(() -> outbox.enqueuePublished(event))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private UUID registration(UUID user, boolean validSession) {
        UUID session = UUID.randomUUID();
        UUID registration = UUID.randomUUID();
        update("INSERT INTO auth_sessions (id, user_id) VALUES (?, ?)", session, user);
        if (validSession) {
            update("""
                    INSERT INTO refresh_tokens (user_id, token_hash, expires_at, session_id)
                    VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '1 day', ?)
                    """, user, UUID.randomUUID().toString().replace("-", "").repeat(2), session);
        }
        update("""
                INSERT INTO push_registrations (id, user_id, device_id, recipient_value, session_id)
                VALUES (?, ?, ?, ?, ?)
                """, registration, user, UUID.randomUUID(), UUID.randomUUID().toString(), session);
        return registration;
    }

    private FeedPushOutbox.FeedPublished event() {
        return new FeedPushOutbox.FeedPublished(UUID.randomUUID(), feed, Instant.now());
    }

    private void update(String sql, Object... values) {
        transactions.executeWithoutResult(status -> {
            var query = entityManager.createNativeQuery(sql);
            for (int i = 0; i < values.length; i++) query.setParameter(i + 1, values[i]);
            query.executeUpdate();
        });
    }

    private long number(String sql, Object... values) {
        return transactions.execute(status -> {
            var query = entityManager.createNativeQuery(sql);
            for (int i = 0; i < values.length; i++) query.setParameter(i + 1, values[i]);
            return ((Number) query.getSingleResult()).longValue();
        });
    }
}
