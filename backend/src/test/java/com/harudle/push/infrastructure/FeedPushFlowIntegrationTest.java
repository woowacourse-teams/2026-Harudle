package com.harudle.push.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.firebase.ErrorCode;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.harudle.category.service.port.CategoryReader;
import com.harudle.feed.service.FeedPublicationService;
import com.harudle.feed.service.dto.FeedResult;
import com.harudle.profile.service.port.PublicProfileReader;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class FeedPushFlowIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Autowired private JdbcTemplate jdbc;
    @Autowired private FeedPublicationService publication;
    @Autowired private JdbcPushDeliveryQueue queue;
    @Autowired @Qualifier("serviceClock") private Clock clock;
    @MockitoBean private CategoryReader categories;
    @MockitoBean private PublicProfileReader profiles;
    @MockitoBean private FcmFeedPushSender sender;
    @MockitoBean private JwtDecoder jwtDecoder;

    private final UUID author = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private final UUID diary = UUID.randomUUID();
    private final UUID registration = UUID.randomUUID();
    private long categoryId;

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO users (id, name) VALUES (?, '작성자'), (?, '수신자')", author, recipient);
        jdbc.update("""
                INSERT INTO generation_prompts (storyboard_prompt_text, image_style_prompt_text, image_asset_object_key)
                VALUES ('스토리보드', '이미지 스타일', 'references/push-flow-test.png')
                """);
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, CURRENT_DATE, '일기')",
                diary, author);
        jdbc.update("""
                INSERT INTO diary_generations
                    (id, diary_id, prompt_id, idempotency_key, request_fingerprint, status, image_object_key)
                VALUES (?, ?, (SELECT id FROM generation_prompts WHERE image_asset_object_key = ?),
                    ?, ?, 'SUCCEEDED', 'generated/push-flow-test.webp')
                """, UUID.randomUUID(), diary, "references/push-flow-test.png",
                UUID.randomUUID(), "a".repeat(64));
        registerRecipient();
        categoryId = jdbc.queryForObject("SELECT id FROM categories WHERE name = '일상'", Long.class);
        when(categories.lockActiveForPublication(categoryId))
                .thenReturn(new CategoryReader.Category(categoryId, "일상", 0, true));
        when(profiles.readAll(Set.of(author))).thenReturn(Map.of(author,
                new PublicProfileReader.Profile(author, "작성자", URI.create("https://example.com/profile.png"))));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", author, recipient);
        jdbc.update("DELETE FROM generation_prompts WHERE image_asset_object_key = ?",
                "references/push-flow-test.png");
    }

    @Test
    @DisplayName("피드를 게시하면 수신자 작업 하나가 예약되고 워커가 FCM 수락 후 SENT로 저장한다")
    void publishesSendsAndRecordsResult() throws Exception {
        FeedResult feed = publication.publish(author, diary, categoryId);
        UUID eventId = eventId(feed.id());
        assertThat(status(feed.id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE feed_id = ?",
                Integer.class, feed.id())).isEqualTo(1);

        when(sender.send("FID-1", eventId, feed.id())).thenReturn("accepted-message-id");
        new FeedPushDeliveryWorker(queue, sender, clock).runBatch();

        verify(sender).send("FID-1", eventId, feed.id());
        assertThat(status(feed.id())).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT sent_at IS NOT NULL FROM notification_outbox WHERE feed_id = ?",
                Boolean.class, feed.id())).isTrue();
    }

    @Test
    @DisplayName("FCM이 일시 오류를 반환해도 피드 게시는 유지되고 같은 작업이 재시도를 기다린다")
    void keepsPublishedFeedWhenFcmFails() throws Exception {
        FeedResult feed = publication.publish(author, diary, categoryId);
        UUID eventId = eventId(feed.id());
        FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
        when(exception.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNAVAILABLE);
        when(exception.getErrorCode()).thenReturn(ErrorCode.UNAVAILABLE);
        when(sender.send("FID-1", eventId, feed.id())).thenThrow(exception);

        Instant beforeRun = clock.instant();
        new FeedPushDeliveryWorker(queue, sender, clock).runBatch();

        verify(sender).send("FID-1", eventId, feed.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feeds WHERE id = ?", Integer.class, feed.id()))
                .isEqualTo(1);
        assertThat(status(feed.id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM notification_outbox WHERE feed_id = ?",
                Integer.class, feed.id())).isEqualTo(1);
        Instant availableAt = jdbc.queryForObject("SELECT available_at FROM notification_outbox WHERE feed_id = ?",
                OffsetDateTime.class, feed.id()).toInstant();
        assertThat(availableAt).isAfter(beforeRun.plusSeconds(9));
    }

    private void registerRecipient() {
        UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO auth_sessions (id, user_id) VALUES (?, ?)", session, recipient);
        jdbc.update("""
                INSERT INTO refresh_tokens (user_id, token_hash, expires_at, session_id)
                VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '1 day', ?)
                """, recipient, UUID.randomUUID().toString().replace("-", "").repeat(2), session);
        jdbc.update("""
                INSERT INTO push_registrations (id, user_id, device_id, recipient_value, session_id)
                VALUES (?, ?, ?, 'FID-1', ?)
                """, registration, recipient, UUID.randomUUID(), session);
    }

    private UUID eventId(UUID feedId) {
        return jdbc.queryForObject("SELECT event_id FROM notification_outbox WHERE feed_id = ?",
                UUID.class, feedId);
    }

    private String status(UUID feedId) {
        return jdbc.queryForObject("SELECT status FROM notification_outbox WHERE feed_id = ?",
                String.class, feedId);
    }
}
