package com.harudle.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.when;

import com.harudle.category.service.port.CategoryReader;
import com.harudle.diary.repository.DiaryLifecycleRepository;
import com.harudle.feed.query.FeedSort;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.feed.service.port.FeedInteractionPort;
import com.harudle.feed.service.port.FeedInteractionPort.CounterChange;
import com.harudle.feed.service.port.FeedInteractionPort.CounterKind;
import com.harudle.feed.service.port.FeedLikeReader;
import com.harudle.profile.service.port.PublicProfileReader;
import com.harudle.push.service.port.FeedPushOutbox;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class FeedListAndInteractionPersistenceTest {
    private static final UUID AUTHOR = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID FIRST = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("80000000-0000-0000-0000-000000000002");
    private static final UUID THIRD = UUID.fromString("f0000000-0000-0000-0000-000000000003");
    private static final UUID OLD = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final Instant PUBLISHED = Instant.parse("2026-10-11T01:00:00.123456Z");
    private static final String REFERENCE = "references/feed-list-interaction-test.png";

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
    @PersistenceContext private EntityManager entityManager;
    @Autowired private TransactionTemplate transactions;
    @Autowired private FeedListService lists;
    @Autowired private FeedInteractionPort interactions;
    @Autowired private FeedDeletionService deletion;
    @Autowired private DiaryLifecycleRepository diaryLifecycle;
    @MockitoBean private CategoryReader categories;
    @MockitoBean private PublicProfileReader profiles;
    @MockitoBean private FeedLikeReader likes;
    @MockitoBean private FeedPushOutbox outbox;
    @MockitoBean private JwtDecoder jwtDecoder;
    private final Map<UUID, UUID> diaryIds = new HashMap<>();
    private long dailyCategory;
    private long otherCategory;

    @BeforeEach
    void setUp() {
        update("INSERT INTO users (id, name) VALUES (?, '캐모')", AUTHOR);
        update("""
                INSERT INTO generation_prompts (storyboard_prompt_text, image_style_prompt_text, image_asset_object_key)
                VALUES ('스토리보드', '이미지 스타일', ?)
                """, REFERENCE);
        dailyCategory = categoryId("일상");
        otherCategory = categoryId("우테코");
        when(categories.get(dailyCategory)).thenReturn(new CategoryReader.Category(dailyCategory, "일상", 0, true));
        when(categories.get(otherCategory)).thenReturn(new CategoryReader.Category(otherCategory, "우테코", 1, true));
        when(profiles.readAll(anySet())).thenReturn(Map.of(AUTHOR,
                new PublicProfileReader.Profile(AUTHOR, "캐모", URI.create("https://example.com/profile.png"))));
        when(likes.findLikedFeedIds(org.mockito.ArgumentMatchers.any(), anySet())).thenReturn(Set.of(FIRST));
        createFeed(FIRST, dailyCategory, PUBLISHED, 9);
        createFeed(SECOND, dailyCategory, PUBLISHED, 9);
        createFeed(THIRD, dailyCategory, PUBLISHED, 3);
        createFeed(OLD, otherCategory, Instant.parse("2025-01-01T00:00:00Z"), 9);
    }

    @AfterEach
    void tearDown() {
        update("DELETE FROM users WHERE id = ?", AUTHOR);
        update("DELETE FROM generation_prompts WHERE image_asset_object_key = ?", REFERENCE);
    }

    @Test
    void latestCursorUsesDatabaseUuidOrderAndDoesNotSkipTimestampTies() {
        var first = lists.getList(null, FeedSort.LATEST, dailyCategory, null, 2);
        assertThat(first.items()).extracting(item -> item.id()).containsExactly(THIRD, SECOND);
        assertThat(first.hasNext()).isTrue();
        var last = lists.getList(null, FeedSort.LATEST, dailyCategory, first.nextCursor(), 2);
        assertThat(last.items()).extracting(item -> item.id()).containsExactly(FIRST);
        assertThat(last.hasNext()).isFalse();
        assertThat(last.nextCursor()).isNull();
    }

    @Test
    void popularCursorOrdersByCountsTimeAndUuidWithoutDateCutoff() {
        var first = lists.getList(AUTHOR, FeedSort.POPULAR, null, null, 2);
        assertThat(first.items()).extracting(item -> item.id()).containsExactly(SECOND, FIRST);
        assertThat(first.items().getLast().likedByMe()).isTrue();
        var last = lists.getList(AUTHOR, FeedSort.POPULAR, null, first.nextCursor(), 2);
        assertThat(last.items()).extracting(item -> item.id()).containsExactly(OLD, THIRD);
        assertThat(last.hasNext()).isFalse();
    }

    @Test
    void archiveDoesNotHideExistingCategoryFeeds() {
        update("UPDATE categories SET is_active = FALSE WHERE id = ?", dailyCategory);
        when(categories.get(dailyCategory)).thenReturn(new CategoryReader.Category(dailyCategory, "일상", 0, false));
        try {
            assertThat(lists.getList(null, FeedSort.LATEST, dailyCategory, null, 20).items()).hasSize(3);
        } finally {
            update("UPDATE categories SET is_active = TRUE WHERE id = ?", dailyCategory);
        }
    }

    @Test
    void excludesDeletedFeedsDiariesAndUnfinishedImagesBeforePagination() {
        update("UPDATE feeds SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", FIRST);
        update("UPDATE diaries SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", diaryIds.get(SECOND));
        update("UPDATE diary_generations SET status = 'PROCESSING', image_object_key = NULL WHERE diary_id = ?",
                diaryIds.get(THIRD));
        var page = lists.getList(null, FeedSort.LATEST, null, null, 1);
        assertThat(page.items()).extracting(item -> item.id()).containsExactly(OLD);
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void excludesInactiveAuthorsEvenIfProfileAdapterStillReturnsThem() {
        update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", AUTHOR);
        assertThat(lists.getList(null, FeedSort.LATEST, null, null, 2).items()).isEmpty();
        assertThatThrownBy(() -> transactions.execute(status -> interactions.lockActive(THIRD)))
                .isInstanceOf(FeedNotFoundException.class);
    }

    @Test
    void bothInteractionOperationsRequireCallersTransaction() {
        assertThatThrownBy(() -> interactions.lockActive(THIRD)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> interactions.adjustCounter(THIRD, CounterKind.LIKE, CounterChange.ADDED))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void rollsBackLikeRowAndCounterTogetherWhenCallerFails() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            interactions.lockActive(THIRD);
            executeUpdate("INSERT INTO feed_likes (id, feed_id, user_id) VALUES (?, ?, ?)",
                    UUID.randomUUID(), THIRD, AUTHOR);
            interactions.adjustCounter(THIRD, CounterKind.LIKE, CounterChange.ADDED);
            throw new IllegalStateException("후속 알림 저장 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(number("SELECT COUNT(*) FROM feed_likes WHERE feed_id = ?", THIRD)).isZero();
        assertThat(number("SELECT like_count FROM feeds WHERE id = ?", THIRD)).isEqualTo(3);
    }

    @Test
    void concurrentCounterUpdatesDoNotLoseIncrements() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        try {
            var futures = IntStream.range(0, 12).mapToObj(index -> executor.submit(() ->
                    transactions.execute(status -> {
                        interactions.lockActive(THIRD);
                        return interactions.adjustCounter(THIRD, CounterKind.LIKE, CounterChange.ADDED);
                    }))).toList();
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
            assertThat(number("SELECT like_count FROM feeds WHERE id = ?", THIRD)).isEqualTo(15);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void refusesNegativeCountersAndDeletedTargets() {
        assertThatThrownBy(() -> transactions.execute(status ->
                interactions.adjustCounter(THIRD, CounterKind.COMMENT, CounterChange.REMOVED)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(number("SELECT comment_count FROM feeds WHERE id = ?", THIRD)).isZero();
        update("UPDATE diaries SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", diaryIds.get(THIRD));
        assertThatThrownBy(() -> transactions.execute(status -> interactions.lockActive(THIRD)))
                .isInstanceOf(FeedNotFoundException.class);
        update("UPDATE feeds SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", FIRST);
        assertThatThrownBy(() -> transactions.execute(status -> interactions.lockActive(FIRST)))
                .isInstanceOf(FeedNotFoundException.class);
    }

    @Test
    void holdsFeedLockWithoutLockingOriginalDiary() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
            interactions.lockActive(THIRD);
            held.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("잠금 해제 대기 초과");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }));
        try {
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            transactions.executeWithoutResult(status -> {
                executeUpdate("SET LOCAL lock_timeout = '1s'");
                assertThat(diaryLifecycle.findByIdIncludingDeletedForUpdate(diaryIds.get(THIRD))).isPresent();
            });
        } finally {
            release.countDown();
            try {
                holder.get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void deletionWaitsForCounterTransactionAndPreservesItsCommittedCount() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var held = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var counter = executor.submit(() -> transactions.executeWithoutResult(status -> {
                interactions.lockActive(THIRD);
                interactions.adjustCounter(THIRD, CounterKind.LIKE, CounterChange.ADDED);
                held.countDown();
                awaitRelease(release);
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var removal = executor.submit(() -> {
                started.countDown();
                deletion.delete(AUTHOR, THIRD);
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> removal.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            counter.get(10, TimeUnit.SECONDS);
            removal.get(10, TimeUnit.SECONDS);
            assertThat(number("SELECT like_count FROM feeds WHERE id = ?", THIRD)).isEqualTo(4);
            assertThat(number("SELECT COUNT(*) FROM feeds WHERE id = ? AND deleted_at IS NOT NULL", THIRD))
                    .isEqualTo(1);
            assertThatThrownBy(() -> transactions.execute(status ->
                    interactions.adjustCounter(THIRD, CounterKind.LIKE, CounterChange.ADDED)))
                    .isInstanceOf(FeedNotFoundException.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void deletionHoldsFeedLockWithoutLockingOriginalDiary() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(outbox.cancelUnsentByFeed(THIRD)).thenAnswer(invocation -> {
            held.countDown();
            awaitRelease(release);
            return 0;
        });
        var removal = executor.submit(() -> deletion.delete(AUTHOR, THIRD));
        try {
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            transactions.executeWithoutResult(status -> {
                executeUpdate("SET LOCAL lock_timeout = '1s'");
                assertThat(diaryLifecycle.findByIdIncludingDeletedForUpdate(diaryIds.get(THIRD))).isPresent();
            });
        } finally {
            release.countDown();
            try {
                removal.get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("잠금 해제 대기 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void createFeed(UUID feedId, long categoryId, Instant at, int likeCount) {
        UUID diaryId = UUID.randomUUID();
        diaryIds.put(feedId, diaryId);
        update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, ?, '개인 원문')",
                diaryId, AUTHOR, LocalDate.of(2026, 10, 11));
        update("""
                INSERT INTO diary_generations
                    (id, diary_id, prompt_id, idempotency_key, request_fingerprint, status, image_object_key)
                VALUES (?, ?, (SELECT id FROM generation_prompts WHERE image_asset_object_key = ?),
                    ?, ?, 'SUCCEEDED', ?)
                """, UUID.randomUUID(), diaryId, REFERENCE, UUID.randomUUID(), "a".repeat(64),
                "generated/feed-list/" + feedId + ".webp");
        update("INSERT INTO feeds (id, diary_id, category_id, published_at, like_count) VALUES (?, ?, ?, ?, ?)",
                feedId, diaryId, categoryId, at, likeCount);
    }

    private long categoryId(String name) {
        return transactions.execute(status -> ((Number) entityManager.createNativeQuery(
                "SELECT id FROM categories WHERE name = ?").setParameter(1, name).getSingleResult()).longValue());
    }

    private long number(String sql, Object parameter) {
        return transactions.execute(status -> ((Number) entityManager.createNativeQuery(sql)
                .setParameter(1, parameter).getSingleResult()).longValue());
    }

    private void update(String sql, Object... parameters) {
        transactions.executeWithoutResult(status -> executeUpdate(sql, parameters));
    }

    private void executeUpdate(String sql, Object... parameters) {
        Query query = entityManager.createNativeQuery(sql);
        IntStream.range(0, parameters.length).forEach(index -> query.setParameter(index + 1, parameters[index]));
        query.executeUpdate();
    }
}
