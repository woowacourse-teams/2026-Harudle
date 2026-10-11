package com.harudle.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.category.service.port.CategoryReader;
import com.harudle.diary.service.DiaryDeletionService;
import com.harudle.diary.service.DiaryQueryService;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.diary.service.exception.DiaryNotFoundException;
import com.harudle.diary.service.exception.DiaryNotPublishableException;
import com.harudle.feed.repository.FeedRepository;
import com.harudle.feed.service.dto.FeedResult;
import com.harudle.feed.service.exception.DiaryAlreadyPublishedException;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.feed.service.port.PublishedFeedReader;
import com.harudle.profile.service.port.PublicProfileReader;
import com.harudle.push.service.port.FeedPushOutbox;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class FeedPublicationPersistenceTest {

    private static final UUID ACTOR = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID DIARY = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
    private static final UUID GENERATION = UUID.fromString("550e8400-e29b-41d4-a716-446655440003");

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @PersistenceContext private EntityManager entityManager;
    @Autowired private TransactionTemplate transactions;
    @Autowired private FeedPublicationService publication;
    @Autowired private FeedQueryService queries;
    @Autowired private FeedRepository feeds;
    @Autowired private DiaryDeletionService diaryDeletion;
    @Autowired private DiaryQueryService diaryQueries;
    @Autowired private PublishedFeedReader publishedFeeds;
    @MockitoBean private CategoryReader categories;
    @MockitoBean private PublicProfileReader profiles;
    @MockitoBean private FeedPushOutbox outbox;
    @MockitoBean private JwtDecoder jwtDecoder;
    private long categoryId;

    @BeforeEach
    void setUp() {
        update("INSERT INTO users (id, name) VALUES (?, '캐모')", ACTOR);
        update("""
                INSERT INTO generation_prompts (storyboard_prompt_text, image_style_prompt_text, image_asset_object_key)
                VALUES ('스토리보드', '이미지 스타일', 'references/feed-publication-test.png')
                """);
        update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, ?, ?)",
                DIARY, ACTOR, LocalDate.of(2026, 10, 11), "공개하면 안 되는 개인 일기 원문");
        update("""
                INSERT INTO diary_generations
                    (id, diary_id, prompt_id, idempotency_key, request_fingerprint, status, image_object_key)
                VALUES (?, ?, (SELECT id FROM generation_prompts WHERE image_asset_object_key = ?),
                    ?, ?, 'SUCCEEDED', 'generated/feed-publication-test.webp')
                """, GENERATION, DIARY, "references/feed-publication-test.png", UUID.randomUUID(), "a".repeat(64));
        categoryId = transactions.execute(status ->
                ((Number) entityManager.createNativeQuery("SELECT id FROM categories WHERE name = '일상'")
                        .getSingleResult()).longValue());
        var category = new CategoryReader.Category(categoryId, "일상", 0, true);
        when(categories.lockActiveForPublication(categoryId)).thenReturn(category);
        when(categories.get(categoryId)).thenReturn(category);
        when(profiles.readAll(Set.of(ACTOR))).thenReturn(Map.of(ACTOR,
                new PublicProfileReader.Profile(ACTOR, "캐모", URI.create("https://example.com/profile.png"))));
    }

    @AfterEach
    void tearDown() {
        update("DELETE FROM users WHERE id = ?", ACTOR);
        update("DELETE FROM generation_prompts WHERE image_asset_object_key = ?", "references/feed-publication-test.png");
    }

    @Test
    void publishesAndReadsUsingOriginalImageWithoutExposingDiaryText() {
        FeedResult created = publication.publish(ACTOR, DIARY, categoryId);
        FeedResult detail = queries.getDetail(null, created.id());
        assertThat(detail.id()).isEqualTo(created.id());
        assertThat(detail.publishedAt()).isEqualTo(created.publishedAt());
        assertThat(detail.imageObjectKey()).isEqualTo("generated/feed-publication-test.webp");
        assertThat(detail.isMine()).isFalse();
        assertThat(detail.likedByMe()).isFalse();
        assertThat(feeds.existsByDiaryIdAndDeletedAtIsNull(DIARY)).isTrue();
        assertThat(diaryQueries.getDetail(ACTOR, DIARY).publishedFeedId()).isEqualTo(created.id());
        assertThat(publishedFeeds.findByDiaryIds(Set.of(DIARY))).containsEntry(DIARY, created.id());
    }

    @Test
    void unpublishedDiaryReturnsNullFeedId() {
        assertThat(diaryQueries.getDetail(ACTOR, DIARY).publishedFeedId()).isNull();
        assertThat(publishedFeeds.findByDiaryIds(Set.of(DIARY))).isEmpty();
        assertThat(publishedFeeds.findByDiaryIds(Set.of())).isEmpty();
    }

    @Test
    void bulkPublicationLookupExcludesDeletedAndUnrequestedDiaries() {
        UUID firstFeed = publication.publish(ACTOR, DIARY, categoryId).id();
        UUID secondDiary = UUID.randomUUID();
        UUID secondFeed = insertAdditionalFeed(secondDiary);
        UUID deletedDiary = UUID.randomUUID();
        insertAdditionalFeed(deletedDiary);
        update("UPDATE diaries SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deletedDiary);
        UUID diaryWithDeletedFeed = UUID.randomUUID();
        UUID deletedFeed = insertAdditionalFeed(diaryWithDeletedFeed);
        update("UPDATE feeds SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deletedFeed);
        insertAdditionalFeed(UUID.randomUUID());

        assertThat(publishedFeeds.findByDiaryIds(Set.of(
                DIARY, secondDiary, deletedDiary, diaryWithDeletedFeed, UUID.randomUUID()
        ))).isEqualTo(Map.of(DIARY, firstFeed, secondDiary, secondFeed));
    }

    @Test
    void concurrentRequestsCreateOnlyOneActiveFeedAndOnePushEvent() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures = IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("동시 게시 시작 대기 초과");
                }
                try {
                    return publication.publish(ACTOR, DIARY, categoryId);
                } catch (DiaryAlreadyPublishedException duplicate) {
                    return duplicate;
                }
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> results = List.of(futures.get(0).get(10, TimeUnit.SECONDS),
                    futures.get(1).get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(FeedResult.class::isInstance).count()).isEqualTo(1);
            assertThat(results.stream().filter(DiaryAlreadyPublishedException.class::isInstance).count()).isEqualTo(1);
            assertThat(feeds.count()).isEqualTo(1);
            verify(outbox).enqueuePublished(any());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rollsBackFeedWhenPushReservationFailsAndAllowsRetry() {
        when(outbox.enqueuePublished(any()))
                .thenThrow(new IllegalStateException("예약 저장 실패"))
                .thenReturn(0);
        assertThatThrownBy(() -> publication.publish(ACTOR, DIARY, categoryId))
                .isInstanceOf(IllegalStateException.class);
        assertThat(feeds.existsByDiaryIdAndDeletedAtIsNull(DIARY)).isFalse();
        assertThat(publication.publish(ACTOR, DIARY, categoryId).id()).isNotNull();
    }

    @Test
    void hidesFeedAfterOriginalDiaryDeletion() {
        FeedResult created = publication.publish(ACTOR, DIARY, categoryId);
        diaryDeletion.delete(ACTOR, DIARY);
        assertThat(feeds.findById(created.id()))
                .hasValueSatisfying(feed -> assertThat(feed.isDeleted()).isTrue());
        verify(outbox).cancelUnsentByFeed(created.id());
        assertThatThrownBy(() -> queries.getDetail(null, created.id())).isInstanceOf(FeedNotFoundException.class);
        assertThat(publishedFeeds.findByDiaryIds(Set.of(DIARY))).isEmpty();
        assertThatThrownBy(() -> diaryQueries.getDetail(ACTOR, DIARY)).isInstanceOf(DiaryNotFoundException.class);
    }

    @Test
    void hidesLogicallyDeletedFeedAndAllowsNewPublication() {
        FeedResult first = publication.publish(ACTOR, DIARY, categoryId);
        update("UPDATE feeds SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", first.id());
        assertThatThrownBy(() -> queries.getDetail(null, first.id())).isInstanceOf(FeedNotFoundException.class);
        assertThat(diaryQueries.getDetail(ACTOR, DIARY).publishedFeedId()).isNull();
        FeedResult second = publication.publish(ACTOR, DIARY, categoryId);
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(feeds.count()).isEqualTo(2);
        assertThat(diaryQueries.getDetail(ACTOR, DIARY).publishedFeedId()).isEqualTo(second.id());
        assertThat(publishedFeeds.findByDiaryIds(Set.of(DIARY))).isEqualTo(Map.of(DIARY, second.id()));
    }

    @Test
    void rejectsOtherOwnersDiary() {
        assertThatThrownBy(() -> publication.publish(UUID.randomUUID(), DIARY, categoryId))
                .isInstanceOf(DiaryAccessDeniedException.class);
        assertThat(feeds.count()).isZero();
    }

    @Test
    void rejectsUnfinishedImageGeneration() {
        update("UPDATE diary_generations SET status = 'PROCESSING', image_object_key = NULL WHERE id = ?", GENERATION);
        assertThatThrownBy(() -> publication.publish(ACTOR, DIARY, categoryId))
                .isInstanceOf(DiaryNotPublishableException.class);
        assertThat(feeds.count()).isZero();
    }

    private UUID insertAdditionalFeed(UUID diaryId) {
        update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, ?, ?)",
                diaryId, ACTOR, LocalDate.of(2026, 10, 11), "개인 일기 원문");
        UUID feedId = UUID.randomUUID();
        update("INSERT INTO feeds (id, diary_id, category_id) VALUES (?, ?, ?)", feedId, diaryId, categoryId);
        return feedId;
    }

    private void update(String sql, Object... parameters) {
        transactions.executeWithoutResult(status -> {
            Query query = entityManager.createNativeQuery(sql);
            IntStream.range(0, parameters.length).forEach(index -> query.setParameter(index + 1, parameters[index]));
            query.executeUpdate();
        });
    }
}
