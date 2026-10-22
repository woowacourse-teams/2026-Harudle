package com.harudle.push.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.generation.diary.service.DiaryGenerationWorker;
import com.harudle.push.service.port.FeedPushOutbox;
import com.harudle.support.BlockDatabaseTestSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "harudle.generation.adapters.enabled=false",
        "harudle.push.fcm.enabled=false"
})
class BlockedPushDeliveryPersistenceTest extends BlockDatabaseTestSupport {
    @Autowired private FeedPushOutbox outbox;
    @Autowired private JdbcPushDeliveryQueue queue;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean(enforceOverride = true) private DiaryGenerationWorker generationWorker;

    private final UUID author = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private final UUID unrelated = UUID.randomUUID();
    private final UUID diary = UUID.randomUUID();
    private final UUID feed = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO users (id, name) VALUES (?, '작성자'), (?, '수신자'), (?, '다른 수신자')",
                author, recipient, unrelated);
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, CURRENT_DATE, '푸시 검증')",
                diary, author);
        jdbc.update("INSERT INTO feeds (id, diary_id, category_id) VALUES (?, ?, "
                + "(SELECT id FROM categories WHERE name = '일상'))", feed, diary);
        register(recipient);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE id IN (?, ?, ?)", author, recipient, unrelated);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void doesNotReservePushInEitherBlockDirectionButKeepsUnrelatedRecipient(boolean reverse) {
        register(unrelated);
        block(reverse);
        var event = event();

        int created = transactions.execute(status -> outbox.enqueuePublished(event));

        assertThat(created).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE event_id = ? AND recipient_user_id = ?",
                event.eventId(), recipient)).isZero();
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE event_id = ? AND recipient_user_id = ?",
                event.eventId(), unrelated)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rechecksBothBlockDirectionsAfterClaimAndCancelsWithoutSending(boolean reverse) {
        transactions.executeWithoutResult(status -> outbox.enqueuePublished(event()));
        Instant now = Instant.now();
        var claim = queue.claim(now).orElseThrow();
        assertThat(queue.target(claim, now)).isPresent();

        block(reverse);

        assertThat(queue.target(claim, now)).isEmpty();
        assertThat(queue.cancel(claim, now)).isTrue();
        assertThat(queue.sent(claim, now)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE id = ?",
                String.class, claim.id())).isEqualTo("CANCELLED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"diary", "author"})
    void skipsDeliveryWhenDiaryOrAuthorWasDeletedAfterReservation(String deletedTarget) {
        transactions.executeWithoutResult(status -> outbox.enqueuePublished(event()));
        Instant now = Instant.now();
        var claim = queue.claim(now).orElseThrow();
        if ("diary".equals(deletedTarget)) {
            jdbc.update("UPDATE diaries SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", diary);
        } else {
            jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", author);
        }

        assertThat(queue.target(claim, now)).isEmpty();
    }

    @Test
    void activeUnblockedRecipientStillReceivesCurrentDeviceTarget() {
        transactions.executeWithoutResult(status -> outbox.enqueuePublished(event()));
        Instant now = Instant.now();
        var claim = queue.claim(now).orElseThrow();

        assertThat(queue.target(claim, now)).contains(new JdbcPushDeliveryQueue.Target("block-test-fid-" + recipient, 0));
    }

    private void block(boolean reverse) {
        jdbc.update("INSERT INTO user_blocks (id, blocker_id, blocked_id, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), reverse ? recipient : author, reverse ? author : recipient);
    }

    private void register(UUID userId) {
        UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO auth_sessions (id, user_id) VALUES (?, ?)", session, userId);
        jdbc.update("INSERT INTO refresh_tokens (user_id, token_hash, expires_at, session_id) "
                        + "VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '1 day', ?)",
                userId, UUID.randomUUID().toString().replace("-", "").repeat(2), session);
        jdbc.update("INSERT INTO push_registrations (id, user_id, device_id, recipient_value, session_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, UUID.randomUUID(), "block-test-fid-" + userId, session);
    }

    private FeedPushOutbox.FeedPublished event() {
        return new FeedPushOutbox.FeedPublished(UUID.randomUUID(), feed, Instant.now());
    }

    private long number(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
