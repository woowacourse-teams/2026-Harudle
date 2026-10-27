package com.harudle.push.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.push.service.port.FeedPushOutbox;
import com.harudle.support.AdminModerationDatabaseTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ModeratedFeedPushPersistenceTest extends AdminModerationDatabaseTestSupport {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FeedPushOutbox outbox;
    @Autowired private JdbcPushDeliveryQueue queue;

    private UUID feed;
    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        UUID author = user();
        UUID reader = user();
        UUID diary = UUID.randomUUID();
        feed = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, CURRENT_DATE, '일기')",
                diary, author);
        jdbc.update("""
                INSERT INTO feeds (id, diary_id, category_id, published_at)
                VALUES (?, ?, (SELECT id FROM categories WHERE name = '일상'), ?)
                """, feed, diary, Timestamp.from(now));
        jdbc.update("INSERT INTO auth_sessions (id, user_id) VALUES (?, ?)", session, reader);
        jdbc.update("""
                INSERT INTO refresh_tokens (user_id, token_hash, expires_at, session_id)
                VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '1 day', ?)
                """, reader, UUID.randomUUID().toString().replace("-", "").repeat(2), session);
        jdbc.update("""
                INSERT INTO push_registrations (id, user_id, device_id, recipient_value, session_id)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), reader, UUID.randomUUID(), UUID.randomUUID().toString(), session);
    }

    @Test
    @DisplayName("숨긴 피드는 새 푸시를 예약하지 않고 숨김 해제 후 새 이벤트는 예약할 수 있다")
    void skipNewPushReservationsForHiddenFeeds() {
        restrict(true);
        assertThat(outbox.enqueuePublished(event())).isZero();
        restrict(false);
        assertThat(outbox.enqueuePublished(event())).isEqualTo(1);
    }

    @Test
    @DisplayName("예약 뒤 피드를 숨기면 발송 직전 대상 확인에서 제외하며 이미 예약한 행은 남는다")
    void recheckHiddenFeedWhenResolvingAnAlreadyClaimedPushTarget() {
        assertThat(outbox.enqueuePublished(event())).isEqualTo(1);
        JdbcPushDeliveryQueue.Claim claim = queue.claim(now.plusSeconds(1)).orElseThrow();
        assertThat(queue.target(claim, now.plusSeconds(1))).isPresent();
        restrict(true);
        assertThat(queue.target(claim, now.plusSeconds(1))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE id = ?", Long.class, claim.id()))
                .isEqualTo(1);
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, name) VALUES (?, '푸시회원')", id);
        return id;
    }

    private FeedPushOutbox.FeedPublished event() {
        return new FeedPushOutbox.FeedPublished(UUID.randomUUID(), feed, now);
    }

    private void restrict(boolean restricted) {
        jdbc.update("""
                INSERT INTO moderation_states (target_type, target_id, restricted, version, reason, changed_at)
                VALUES ('FEED', ?, ?, 1, '심사 결과', CURRENT_TIMESTAMP)
                ON CONFLICT (target_type, target_id) DO UPDATE SET restricted = EXCLUDED.restricted,
                    version = moderation_states.version + 1
                """, feed, restricted);
    }
}
