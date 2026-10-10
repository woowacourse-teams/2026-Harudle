package com.harudle.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.category.service.port.CategoryReader;
import com.harudle.diary.repository.DiaryLifecycleRepository;
import com.harudle.diary.service.DiaryQueryService;
import com.harudle.diary.service.exception.DiaryNotFoundException;
import com.harudle.feed.query.FeedSort;
import com.harudle.feed.repository.FeedRepository;
import com.harudle.feed.service.exception.FeedAccessDeniedException;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.feed.service.port.FeedInteractionPort;
import com.harudle.feed.service.port.FeedInteractionPort.CounterChange;
import com.harudle.feed.service.port.FeedInteractionPort.CounterKind;
import com.harudle.feed.service.port.FeedLifecycle;
import com.harudle.profile.service.port.PublicProfileReader;
import com.harudle.push.service.port.FeedPushOutbox;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class FeedDeletionPersistenceTest {
    private static final UUID AUTHOR = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID REACTOR = UUID.fromString("fcd3ba43-78ef-4c82-9224-f65c4620b18c");
    private static final UUID DIARY = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
    private static final UUID GENERATION = UUID.fromString("550e8400-e29b-41d4-a716-446655440003");
    private static final Instant DELETED_AT = Instant.parse("2026-10-11T02:00:00.123456Z");
    private static final String REFERENCE = "references/feed-deletion-test.png";
    private static final String IMAGE = "generated/feed-deletion-test.webp";
    private static final String SOURCE = "공개하면 안 되는 개인 일기 원문";

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
    @PersistenceContext private EntityManager entityManager;
    @Autowired private TransactionTemplate transactions;
    @Autowired private FeedPublicationService publication;
    @Autowired private FeedDeletionService deletion;
    @Autowired private FeedLifecycle lifecycle;
    @Autowired private FeedRepository feeds;
    @Autowired private FeedQueryService queries;
    @Autowired private FeedListService lists;
    @Autowired private DiaryQueryService diaryQueries;
    @Autowired private DiaryLifecycleRepository diaryLifecycle;
    @Autowired private FeedInteractionPort interactions;
    @MockitoBean private CategoryReader categories;
    @MockitoBean private PublicProfileReader profiles;
    @MockitoBean private FeedPushOutbox outbox;
    @MockitoBean private JwtDecoder jwtDecoder;
    private long categoryId;

    @BeforeEach
    void setUp() {
        update("INSERT INTO users (id, name) VALUES (?, '캐모'), (?, '독자')", AUTHOR, REACTOR);
        update("""
                INSERT INTO generation_prompts (storyboard_prompt_text, image_style_prompt_text, image_asset_object_key)
                VALUES ('스토리보드', '이미지 스타일', ?)
                """, REFERENCE);
        update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, ?, ?)",
                DIARY, AUTHOR, LocalDate.of(2026, 10, 11), SOURCE);
        update("""
                INSERT INTO diary_generations
                    (id, diary_id, prompt_id, idempotency_key, request_fingerprint, status, image_object_key)
                VALUES (?, ?, (SELECT id FROM generation_prompts WHERE image_asset_object_key = ?),
                    ?, ?, 'SUCCEEDED', ?)
                """, GENERATION, DIARY, REFERENCE, UUID.randomUUID(), "a".repeat(64), IMAGE);
        categoryId = number("SELECT id FROM categories WHERE name = ?", "일상");
        var category = new CategoryReader.Category(categoryId, "일상", 0, true);
        when(categories.lockActiveForPublication(categoryId)).thenReturn(category);
        when(categories.get(categoryId)).thenReturn(category);
        when(profiles.readAll(Set.of(AUTHOR))).thenReturn(Map.of(AUTHOR,
                new PublicProfileReader.Profile(AUTHOR, "캐모", URI.create("https://example.com/profile.png"))));
        // 푸시 담당 구현 대신 같은 트랜잭션에서 SQL을 실행해 포트 호출과 롤백 경계를 검증한다.
        when(outbox.cancelUnsentByFeed(any())).thenAnswer(invocation -> cancelJobs(invocation.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        update("DELETE FROM users WHERE id IN (?, ?)", AUTHOR, REACTOR);
        update("DELETE FROM generation_prompts WHERE image_asset_object_key = ?", REFERENCE);
    }

    @Test
    void retainsOriginalAndReactionsWhileHidingFeedAndPublicationLink() {
        UUID feedId = publish();
        insertReactions(feedId);

        deletion.delete(AUTHOR, feedId);

        var deleted = feeds.findById(feedId).orElseThrow();
        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.getLikeCount()).isEqualTo(1);
        assertThat(deleted.getCommentCount()).isEqualTo(1);
        var diary = diaryQueries.getDetail(AUTHOR, DIARY);
        assertThat(diary.sourceText()).isEqualTo(SOURCE);
        assertThat(diary.generation().id()).isEqualTo(GENERATION);
        assertThat(diary.generation().imageObjectKey()).isEqualTo(IMAGE);
        assertThat(diary.publishedFeedId()).isNull();
        assertThat(number("SELECT COUNT(*) FROM comments WHERE feed_id = ?", feedId)).isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM feed_likes WHERE feed_id = ?", feedId)).isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM notifications WHERE feed_id = ?", feedId)).isEqualTo(1);
        assertThatThrownBy(() -> queries.getDetail(null, feedId)).isInstanceOf(FeedNotFoundException.class);
        assertThat(lists.getList(null, FeedSort.LATEST, null, null, 20).items()).isEmpty();
        assertThatThrownBy(() -> transactions.execute(status -> interactions.lockActive(feedId)))
                .isInstanceOf(FeedNotFoundException.class);
        assertThatThrownBy(() -> transactions.execute(status ->
                interactions.adjustCounter(feedId, CounterKind.LIKE, CounterChange.ADDED)))
                .isInstanceOf(FeedNotFoundException.class);
    }

    @Test
    void cancelsOnlyPendingAndProcessingJobsAndRetainsTerminalHistory() {
        UUID feedId = publish();
        UUID pending = insertPushJob(feedId, "PENDING");
        UUID processing = insertPushJob(feedId, "PROCESSING");
        UUID sent = insertPushJob(feedId, "SENT");
        UUID cancelled = insertPushJob(feedId, "CANCELLED");
        UUID failed = insertPushJob(feedId, "FAILED");

        deletion.delete(AUTHOR, feedId);

        assertThat(jobStatus(pending)).isEqualTo("CANCELLED");
        assertThat(jobStatus(processing)).isEqualTo("CANCELLED");
        assertThat(jobStatus(sent)).isEqualTo("SENT");
        assertThat(jobStatus(cancelled)).isEqualTo("CANCELLED");
        assertThat(jobStatus(failed)).isEqualTo("FAILED");
        assertThat(number("""
                SELECT COUNT(*) FROM notification_outbox
                WHERE feed_id = ? AND status = 'CANCELLED'
                  AND lock_token IS NULL AND locked_until IS NULL AND sent_at IS NULL
                """, feedId)).isEqualTo(3);
        assertThat(number("SELECT COUNT(*) FROM notification_outbox WHERE feed_id = ?", feedId)).isEqualTo(5);
        verify(outbox).cancelUnsentByFeed(feedId);
    }

    @Test
    void rollsBackFeedAndAlreadyCancelledJobsWhenCancellationFails() {
        UUID feedId = publish();
        UUID jobId = insertPushJob(feedId, "PENDING");
        failAfterCancellingJobs();

        assertThatThrownBy(() -> deletion.delete(AUTHOR, feedId)).isInstanceOf(IllegalStateException.class);

        assertThat(feeds.findById(feedId).orElseThrow().isDeleted()).isFalse();
        assertThat(jobStatus(jobId)).isEqualTo("PENDING");
        assertThat(diaryQueries.getDetail(AUTHOR, DIARY).publishedFeedId()).isEqualTo(feedId);
        assertThat(queries.getDetail(null, feedId).id()).isEqualTo(feedId);
    }

    @Test
    void rejectsAnotherAuthorWithoutChangingFeedOrJobs() {
        UUID feedId = publish();
        UUID jobId = insertPushJob(feedId, "PENDING");

        assertThatThrownBy(() -> deletion.delete(REACTOR, feedId)).isInstanceOf(FeedAccessDeniedException.class);

        assertThat(feeds.findById(feedId).orElseThrow().isDeleted()).isFalse();
        assertThat(jobStatus(jobId)).isEqualTo("PENDING");
        verify(outbox, never()).cancelUnsentByFeed(any());
    }

    @Test
    void repeatedDeletionDoesNotCancelAgainAndRepublicationStartsNewCounters() {
        UUID first = publish();
        update("UPDATE feeds SET like_count = 2, comment_count = 3 WHERE id = ?", first);
        deletion.delete(AUTHOR, first);
        Instant deletedAt = feeds.findById(first).orElseThrow().getDeletedAt();

        assertThatThrownBy(() -> deletion.delete(AUTHOR, first)).isInstanceOf(FeedNotFoundException.class);
        var second = publication.publish(AUTHOR, DIARY, categoryId);

        assertThat(second.id()).isNotEqualTo(first);
        assertThat(second.likeCount()).isZero();
        assertThat(second.commentCount()).isZero();
        var old = feeds.findById(first).orElseThrow();
        assertThat(old.getDeletedAt()).isEqualTo(deletedAt);
        assertThat(old.getLikeCount()).isEqualTo(2);
        assertThat(old.getCommentCount()).isEqualTo(3);
        assertThat(number("SELECT COUNT(*) FROM feeds WHERE diary_id = ?", DIARY)).isEqualTo(2);
        assertThat(diaryQueries.getDetail(AUTHOR, DIARY).publishedFeedId()).isEqualTo(second.id());
        verify(outbox).cancelUnsentByFeed(first);
    }

    @Test
    void lifecycleRequiresCallersTransaction() {
        assertThatThrownBy(() -> lifecycle.deleteByDiary(DIARY, DELETED_AT))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void lifecycleDeletesEvenAfterCallerHasMarkedDiaryDeleted() {
        UUID feedId = publish();
        UUID jobId = insertPushJob(feedId, "PENDING");

        transactions.executeWithoutResult(status -> {
            var diary = diaryLifecycle.findByIdIncludingDeletedForUpdate(DIARY).orElseThrow();
            assertThat(diary.isOwnedBy(AUTHOR)).isTrue();
            diary.delete(DELETED_AT);
            lifecycle.deleteByDiary(DIARY, DELETED_AT);
        });

        assertThat(feeds.findById(feedId).orElseThrow().getDeletedAt()).isEqualTo(DELETED_AT);
        assertThat(jobStatus(jobId)).isEqualTo("CANCELLED");
        assertThat(number("SELECT COUNT(*) FROM diaries WHERE id = ? AND deleted_at IS NOT NULL", DIARY))
                .isEqualTo(1);
        assertThatThrownBy(() -> diaryQueries.getDetail(AUTHOR, DIARY)).isInstanceOf(DiaryNotFoundException.class);
        assertThatThrownBy(() -> queries.getDetail(null, feedId)).isInstanceOf(FeedNotFoundException.class);
    }

    @Test
    void lifecycleCancellationFailureRollsBackCallersDiaryFeedAndJobs() {
        UUID feedId = publish();
        UUID jobId = insertPushJob(feedId, "PENDING");
        failAfterCancellingJobs();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            var diary = diaryLifecycle.findByIdIncludingDeletedForUpdate(DIARY).orElseThrow();
            diary.delete(DELETED_AT);
            lifecycle.deleteByDiary(DIARY, DELETED_AT);
        })).isInstanceOf(IllegalStateException.class);

        assertThat(feeds.findById(feedId).orElseThrow().isDeleted()).isFalse();
        assertThat(jobStatus(jobId)).isEqualTo("PENDING");
        assertThat(diaryQueries.getDetail(AUTHOR, DIARY).publishedFeedId()).isEqualTo(feedId);
    }

    @Test
    void lifecycleDoesNothingForMissingOrAlreadyDeletedFeed() {
        transactions.executeWithoutResult(status -> lifecycle.deleteByDiary(DIARY, DELETED_AT));
        verify(outbox, never()).cancelUnsentByFeed(any());
        UUID feedId = publish();
        deletion.delete(AUTHOR, feedId);
        Instant deletedAt = feeds.findById(feedId).orElseThrow().getDeletedAt();

        transactions.executeWithoutResult(status -> lifecycle.deleteByDiary(DIARY, DELETED_AT));

        assertThat(feeds.findById(feedId).orElseThrow().getDeletedAt()).isEqualTo(deletedAt);
        verify(outbox).cancelUnsentByFeed(feedId);
    }

    private void insertReactions(UUID feedId) {
        UUID commentId = UUID.randomUUID();
        update("INSERT INTO comments (id, feed_id, author_id, content) VALUES (?, ?, ?, '댓글')",
                commentId, feedId, REACTOR);
        update("INSERT INTO feed_likes (id, feed_id, user_id) VALUES (?, ?, ?)", UUID.randomUUID(), feedId, REACTOR);
        update("""
                INSERT INTO notifications (id, recipient_user_id, actor_user_id, feed_id, type, source_action_id)
                VALUES (?, ?, ?, ?, 'COMMENT', ?)
                """, UUID.randomUUID(), AUTHOR, REACTOR, feedId, commentId);
        update("UPDATE feeds SET like_count = 1, comment_count = 1 WHERE id = ?", feedId);
    }

    private UUID publish() {
        return publication.publish(AUTHOR, DIARY, categoryId).id();
    }

    private void failAfterCancellingJobs() {
        doAnswer(invocation -> {
            cancelJobs(invocation.getArgument(0));
            throw new IllegalStateException("예약 취소 후 저장 실패");
        }).when(outbox).cancelUnsentByFeed(any());
    }

    private int cancelJobs(UUID feedId) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        return executeUpdate("""
                UPDATE notification_outbox
                SET status = 'CANCELLED', locked_until = NULL, lock_token = NULL,
                    sent_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE feed_id = ? AND status IN ('PENDING', 'PROCESSING')
                """, feedId);
    }

    private UUID insertPushJob(UUID feedId, String status) {
        UUID registration = UUID.randomUUID();
        update("""
                INSERT INTO push_registrations (id, user_id, device_id, recipient_value)
                VALUES (?, ?, ?, ?)
                """, registration, AUTHOR, UUID.randomUUID(), "token-" + registration);
        UUID jobId = UUID.randomUUID();
        update("""
                INSERT INTO notification_outbox
                    (id, event_id, feed_id, recipient_user_id, push_registration_id,
                     status, locked_until, lock_token, sent_at)
                VALUES (?, ?, ?, ?, ?, ?,
                    CASE WHEN ? = 'PROCESSING' THEN CURRENT_TIMESTAMP + INTERVAL '1 minute' END,
                    CASE WHEN ? = 'PROCESSING' THEN CAST(? AS UUID) END,
                    CASE WHEN ? = 'SENT' THEN CURRENT_TIMESTAMP END)
                """, jobId, UUID.randomUUID(), feedId, AUTHOR, registration, status,
                status, status, UUID.randomUUID(), status);
        return jobId;
    }

    private String jobStatus(UUID jobId) {
        return transactions.execute(status -> (String) entityManager.createNativeQuery(
                "SELECT status FROM notification_outbox WHERE id = ?").setParameter(1, jobId).getSingleResult());
    }

    private long number(String sql, Object parameter) {
        return transactions.execute(status -> ((Number) entityManager.createNativeQuery(sql)
                .setParameter(1, parameter).getSingleResult()).longValue());
    }

    private void update(String sql, Object... parameters) {
        transactions.executeWithoutResult(status -> executeUpdate(sql, parameters));
    }

    private int executeUpdate(String sql, Object... parameters) {
        Query query = entityManager.createNativeQuery(sql);
        IntStream.range(0, parameters.length).forEach(index -> query.setParameter(index + 1, parameters[index]));
        return query.executeUpdate();
    }
}
