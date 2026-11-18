package com.harudle.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.auth.application.ProfileImageStorageException;
import com.harudle.category.service.port.CategoryReader;
import com.harudle.common.testdata.DevTestDataVisibility;
import com.harudle.feed.infrastructure.CachedFeedPreviewImageProvider;
import com.harudle.feed.infrastructure.JpaFeedPreviewReader;
import com.harudle.feed.infrastructure.StoredFeedPreviewImageProvider;
import com.harudle.feed.repository.FeedQueryRepository;
import com.harudle.feed.service.dto.FeedPreviewMetadata;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.feed.service.port.FeedPreviewReader;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import com.harudle.profile.repository.PublicProfileQueryRepository;
import com.harudle.support.FeedPersistenceTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class FeedPreviewVisibilityPersistenceTest extends FeedPersistenceTestSupport {

    private static final String PRIVATE_SOURCE = "복호화하거나 미리보기에 노출하면 안 되는 원문";

    @PersistenceContext private EntityManager entityManager;
    @Autowired private TransactionTemplate transactions;
    @Autowired private FeedPreviewReader queries;
    @Autowired private FeedQueryRepository feedRepository;
    @Autowired private PublicProfileQueryRepository publicProfiles;
    private final UUID authorId = UUID.randomUUID();
    private final String nickname = "미리보기" + authorId.toString().substring(0, 6);
    private final UUID diaryId = UUID.randomUUID();
    private final UUID feedId = UUID.randomUUID();
    private final String referenceKey = "references/feed-preview-" + UUID.randomUUID() + ".png";
    private final String imageKey = "generated/feed-preview-" + UUID.randomUUID() + ".webp";
    private StoredFeedPreviewImageProvider imageProvider;
    private FeedPreviewService previews;

    @BeforeEach
    void setUp() {
        update("INSERT INTO users (id, name, nickname, profile_image_code) VALUES (?, '미리보기 작성자', ?, 1)",
                authorId, nickname);
        update("""
                INSERT INTO generation_prompts (storyboard_prompt_text, image_style_prompt_text, image_asset_object_key)
                VALUES ('스토리보드', '이미지 스타일', ?)
                """, referenceKey);
        // 유효한 암호문이 아니므로 원문을 복호화하는 경로를 잘못 호출하면 테스트가 실패한다.
        update("""
                INSERT INTO diaries (id, user_id, diary_date, source_text, source_text_version)
                VALUES (?, ?, ?, ?, 1)
                """, diaryId, authorId, LocalDate.of(2026, 11, 17), PRIVATE_SOURCE);
        update("""
                INSERT INTO diary_generations
                    (id, diary_id, prompt_id, idempotency_key, request_fingerprint, status, image_object_key)
                VALUES (?, ?, (SELECT id FROM generation_prompts WHERE image_asset_object_key = ?),
                    ?, ?, 'SUCCEEDED', ?)
                """, UUID.randomUUID(), diaryId, referenceKey, UUID.randomUUID(), "a".repeat(64), imageKey);
        long categoryId = transactions.execute(status -> ((Number) entityManager.createNativeQuery(
                "SELECT id FROM categories WHERE name = '일상'").getSingleResult()).longValue());
        update("INSERT INTO feeds (id, diary_id, category_id) VALUES (?, ?, ?)", feedId, diaryId, categoryId);
        when(categories.get(categoryId)).thenReturn(new CategoryReader.Category(categoryId, "일상", 0, true));
        // Spring mock을 추가하지 않아 다른 피드 영속성 테스트의 실행 환경을 그대로 재사용한다.
        imageProvider = mock(StoredFeedPreviewImageProvider.class);
        previews = new FeedPreviewService(queries,
                new CachedFeedPreviewImageProvider(imageProvider, new SimpleMeterRegistry()));
    }

    @AfterEach
    void tearDown() {
        update("DELETE FROM moderation_states WHERE target_type = 'FEED' AND target_id = ?", feedId);
        update("DELETE FROM users WHERE id = ?", authorId);
        update("DELETE FROM generation_prompts WHERE image_asset_object_key = ?", referenceKey);
    }

    @Test
    @DisplayName("공개 성공 피드의 메타는 실제 익명 조회를 사용하고 원문·이미지를 읽지 않는다")
    void publicMetadataDoesNotReadPrivateSourceOrImage() {
        FeedPreviewMetadata metadata = previews.metadata(feedId);

        assertThat(metadata.title()).contains(nickname).doesNotContain(PRIVATE_SOURCE);
        assertThat(metadata.description()).contains("일상").doesNotContain(PRIVATE_SOURCE);
        verifyNoInteractions(imageProvider, profiles);
    }

    @Test
    @DisplayName("공개 이미지 요청은 확인된 저장 키만 전달하고 숨김 뒤 같은 주소의 재요청은 차단한다")
    void imageRechecksPublicVisibilityForEveryRequest() {
        ReferenceImage jpeg = new ReferenceImage(new ByteArrayResource(new byte[] {2}), MediaType.IMAGE_JPEG);
        when(imageProvider.provide(imageKey)).thenReturn(jpeg);

        assertThat(previews.image(feedId).resource()).isEqualTo(jpeg.resource());
        assertThat(previews.image(feedId).resource()).isEqualTo(jpeg.resource());
        verify(imageProvider, times(1)).provide(imageKey);
        clearInvocations(imageProvider);
        hideFeed();

        assertThatThrownBy(() -> previews.metadata(feedId)).isInstanceOf(FeedNotFoundException.class);
        assertThatThrownBy(() -> previews.image(feedId)).isInstanceOf(FeedNotFoundException.class);
        verifyNoInteractions(imageProvider);
    }

    @Test
    @DisplayName("업로드 프로필 사진의 주소 발급이 실패해도 공개 메타와 이미지를 제공한다")
    void uploadedProfileDoesNotRequireSignedProfileImageUrl() {
        String profileKey = "harudle/profile-images/prod/" + authorId + "/" + UUID.randomUUID() + ".webp";
        update("UPDATE users SET profile_image_object_key = ? WHERE id = ?", profileKey, authorId);
        when(profiles.readAll(Set.of(authorId))).thenThrow(
                new ProfileImageStorageException("프로필 사진 서명 실패", profileKey, null));
        ReferenceImage jpeg = new ReferenceImage(new ByteArrayResource(new byte[] {2}), MediaType.IMAGE_JPEG);
        when(imageProvider.provide(imageKey)).thenReturn(jpeg);

        assertThat(previews.metadata(feedId).title()).contains(nickname);
        assertThat(previews.image(feedId).resource()).isEqualTo(jpeg.resource());

        verifyNoInteractions(profiles);
    }

    @Test
    @DisplayName("dev의 테스트 작성자 제외 조건을 실제 익명 조회에 적용한다")
    void devPreviewHidesLoadTestAuthorsFromAnonymousViewer() {
        update("UPDATE users SET primary_email = ? WHERE id = ?", "og-" + authorId + "@test.loadtest.invalid", authorId);
        var devQueries = new JpaFeedPreviewReader(feedRepository, publicProfiles, categories,
                new DevTestDataVisibility("dev"));

        assertThatThrownBy(() -> transactions.execute(status -> devQueries.read(feedId)))
                .isInstanceOf(FeedNotFoundException.class);

        verifyNoInteractions(imageProvider, profiles);
    }

    @ParameterizedTest
    @EnumSource(HiddenReason.class)
    @DisplayName("비공개·삭제·탈퇴·관리자 숨김·미완료 피드는 메타와 이미지 저장소 접근 전에 거절한다")
    void hiddenTargetsNeverReachImageReader(HiddenReason reason) {
        UUID requestedId = feedId;
        switch (reason) {
            case UNPUBLISHED_DIARY -> {
                update("DELETE FROM feeds WHERE id = ?", feedId);
                requestedId = diaryId;
            }
            case DELETED_FEED -> update("UPDATE feeds SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", feedId);
            case DELETED_DIARY -> update("UPDATE diaries SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", diaryId);
            case WITHDRAWN_AUTHOR -> update("DELETE FROM users WHERE id = ?", authorId);
            case SOFT_WITHDRAWN_AUTHOR -> update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", authorId);
            case MISSING_NICKNAME -> update("UPDATE users SET nickname = NULL WHERE id = ?", authorId);
            case MISSING_PROFILE_IMAGE -> update("UPDATE users SET profile_image_code = NULL WHERE id = ?", authorId);
            case MODERATED_FEED -> hideFeed();
            case PENDING_IMAGE, PROCESSING_IMAGE, FAILED_IMAGE -> update(
                    "UPDATE diary_generations SET status = ?, image_object_key = NULL WHERE diary_id = ?",
                    reason.name().replace("_IMAGE", ""), diaryId);
        }
        UUID targetId = requestedId;

        assertThatThrownBy(() -> previews.metadata(targetId)).isInstanceOf(FeedNotFoundException.class);
        assertThatThrownBy(() -> previews.image(targetId)).isInstanceOf(FeedNotFoundException.class);
        verifyNoInteractions(imageProvider, profiles);
    }

    private void hideFeed() {
        update("""
                INSERT INTO moderation_states (target_type, target_id, restricted, version, reason, changed_at)
                VALUES ('FEED', ?, true, 1, '미리보기 숨김 검증', CURRENT_TIMESTAMP)
                """, feedId);
    }

    private void update(String sql, Object... parameters) {
        transactions.executeWithoutResult(status -> {
            Query query = entityManager.createNativeQuery(sql);
            IntStream.range(0, parameters.length).forEach(index -> query.setParameter(index + 1, parameters[index]));
            query.executeUpdate();
        });
    }

    private enum HiddenReason {
        UNPUBLISHED_DIARY, DELETED_FEED, DELETED_DIARY, WITHDRAWN_AUTHOR, SOFT_WITHDRAWN_AUTHOR,
        MISSING_NICKNAME, MISSING_PROFILE_IMAGE, MODERATED_FEED,
        PENDING_IMAGE, PROCESSING_IMAGE, FAILED_IMAGE
    }
}
