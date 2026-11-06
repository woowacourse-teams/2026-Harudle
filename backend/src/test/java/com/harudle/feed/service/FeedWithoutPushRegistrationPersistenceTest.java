package com.harudle.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.diary.service.DiaryDeletionService;
import com.harudle.diary.service.DiaryQueryService;
import com.harudle.diary.service.exception.DiaryNotFoundException;
import com.harudle.feed.repository.FeedRepository;
import com.harudle.support.ServiceIntegrationTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class FeedWithoutPushRegistrationPersistenceTest extends ServiceIntegrationTestSupport {
    private static final UUID AUTHOR = UUID.fromString("550e8400-e29b-41d4-a716-446655440021");
    private static final UUID DIARY = UUID.fromString("550e8400-e29b-41d4-a716-446655440022");
    private static final UUID GENERATION = UUID.fromString("550e8400-e29b-41d4-a716-446655440023");
    private static final String REFERENCE = "references/feed-without-push-test.png";

    @PersistenceContext private EntityManager entityManager;
    @Autowired private TransactionTemplate transactions;
    @Autowired private FeedPublicationService publication;
    @Autowired private FeedDeletionService deletion;
    @Autowired private FeedRepository feeds;
    @Autowired private DiaryDeletionService diaryDeletion;
    @Autowired private DiaryQueryService diaryQueries;
    private long categoryId;

    @BeforeEach
    void setUp() {
        update("INSERT INTO users (id, name, nickname, profile_image_code) VALUES (?, '캐모', '캐모', 1)", AUTHOR);
        update("""
                INSERT INTO generation_prompts (storyboard_prompt_text, image_style_prompt_text, image_asset_object_key)
                VALUES ('스토리보드', '이미지 스타일', ?)
                """, REFERENCE);
        update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, ?, ?)",
                DIARY, AUTHOR, LocalDate.of(2026, 10, 11), "개인 일기 원문");
        update("""
                INSERT INTO diary_generations
                    (id, diary_id, prompt_id, idempotency_key, request_fingerprint, status, image_object_key)
                VALUES (?, ?, (SELECT id FROM generation_prompts WHERE image_asset_object_key = ?),
                    ?, ?, 'SUCCEEDED', 'generated/feed-without-push-test.webp')
                """, GENERATION, DIARY, REFERENCE, UUID.randomUUID(), "a".repeat(64));
        categoryId = number("SELECT id FROM categories WHERE name = ?", "일상");
    }

    @AfterEach
    void tearDown() {
        update("DELETE FROM users WHERE id = ?", AUTHOR);
        update("DELETE FROM generation_prompts WHERE image_asset_object_key = ?", REFERENCE);
    }

    @Test
    @DisplayName("등록된 푸시 기기가 없어도 피드 게시와 조회가 성공하고 발송 작업은 만들지 않는다")
    void publishesWithRealCategoryAndProfileReadersButWithoutPushRegistration() {
        var result = publication.publish(AUTHOR, DIARY, categoryId);

        assertThat(feeds.existsByDiaryIdAndDeletedAtIsNull(DIARY)).isTrue();
        assertThat(diaryQueries.getDetail(AUTHOR, DIARY).publishedFeedId()).isEqualTo(result.id());
        assertThat(result.author().id()).isEqualTo(AUTHOR);
        assertThat(result.category().id()).isEqualTo(categoryId);
        assertThat(number("SELECT COUNT(*) FROM notification_outbox WHERE feed_id = ?", result.id())).isZero();
    }

    @Test
    @DisplayName("등록된 푸시 기기가 없어도 피드를 삭제하면 원래 일기는 남는다")
    void deletesFeedWithoutPushRegistrationAndRetainsOriginalDiary() {
        UUID feedId = publication.publish(AUTHOR, DIARY, categoryId).id();

        deletion.delete(AUTHOR, feedId);

        assertThat(feeds.findById(feedId)).hasValueSatisfying(feed -> assertThat(feed.isDeleted()).isTrue());
        assertThat(diaryQueries.getDetail(AUTHOR, DIARY).publishedFeedId()).isNull();
        assertThat(diaryQueries.getDetail(AUTHOR, DIARY).sourceText()).isEqualTo("개인 일기 원문");
    }

    @Test
    @DisplayName("등록된 푸시 기기가 없어도 일기를 삭제하면 연결된 피드가 함께 삭제된다")
    void deletesDiaryAndConnectedFeedWithoutPushRegistration() {
        UUID feedId = publication.publish(AUTHOR, DIARY, categoryId).id();

        diaryDeletion.delete(AUTHOR, DIARY);

        assertThat(feeds.findById(feedId)).hasValueSatisfying(feed -> assertThat(feed.isDeleted()).isTrue());
        assertThat(number("SELECT COUNT(*) FROM diaries WHERE id = ? AND deleted_at IS NOT NULL", DIARY)).isEqualTo(1);
        assertThatThrownBy(() -> diaryQueries.getDetail(AUTHOR, DIARY)).isInstanceOf(DiaryNotFoundException.class);
    }

    private void update(String sql, Object... parameters) {
        transactions.executeWithoutResult(status -> query(sql, parameters).executeUpdate());
    }

    private long number(String sql, Object... parameters) {
        return transactions.execute(status -> ((Number) query(sql, parameters).getSingleResult()).longValue());
    }

    private Query query(String sql, Object... parameters) {
        Query query = entityManager.createNativeQuery(sql);
        for (int index = 0; index < parameters.length; index++) {
            query.setParameter(index + 1, parameters[index]);
        }
        return query;
    }
}
