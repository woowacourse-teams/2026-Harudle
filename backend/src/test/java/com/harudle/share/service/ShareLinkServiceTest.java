package com.harudle.share.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.diary.service.exception.DiaryNotFoundException;
import com.harudle.generation.service.exception.GenerationInProgressException;
import com.harudle.share.repository.ShareLinkRepository;
import com.harudle.share.service.exception.ShareGenerationFailedException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
class ShareLinkServiceTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final UUID USER_ID = UUID.fromString("fdc5a50e-7bd7-488d-a76c-bcae8d82983b");
    private static final UUID OTHER_USER_ID = UUID.fromString("c092031d-4bfa-4fab-8ac8-bd8664803b79");
    private static final UUID DIARY_ID = UUID.fromString("0999af9c-24d2-41b2-bf22-ab8f15bf7a17");
    private static final UUID GENERATION_ID = UUID.fromString("eb610a27-6833-4d83-bbd3-bc56a196b5dc");

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ShareLinkService shareLinkService;

    @Autowired
    private ShareLinkRepository shareLinkRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        executeUpdate(
                "INSERT INTO users (id, primary_email, name) VALUES (?, ?, ?)",
                USER_ID,
                "share-link@example.com",
                "공유 사용자"
        );
        executeUpdate(
                "INSERT INTO users (id, primary_email, name) VALUES (?, ?, ?)",
                OTHER_USER_ID,
                "other@example.com",
                "다른 사용자"
        );
        executeUpdate("""
                INSERT INTO generation_prompts (
                    storyboard_prompt_text,
                    image_style_prompt_text,
                    image_asset_object_key
                ) VALUES (?, ?, ?)
                """, "스토리보드 프롬프트", "이미지 스타일 프롬프트", "references/share-style.png");
        executeUpdate("""
                INSERT INTO diaries (id, user_id, diary_date, source_text)
                VALUES (?, ?, ?, ?)
                """, DIARY_ID, USER_ID, LocalDate.of(2026, 8, 6), "친구와 카페에 갔다.");
        insertGeneration("SUCCEEDED", null, "images/share-diary.png");
    }

    @AfterEach
    void tearDown() {
        executeUpdate("DELETE FROM share_links WHERE generation_id = ?", GENERATION_ID);
        executeUpdate("DELETE FROM diary_generations WHERE id = ?", GENERATION_ID);
        executeUpdate("DELETE FROM diaries WHERE id = ?", DIARY_ID);
        executeUpdate(
                "DELETE FROM generation_prompts WHERE image_asset_object_key = ?",
                "references/share-style.png"
        );
        executeUpdate("DELETE FROM users WHERE id IN (?, ?)", USER_ID, OTHER_USER_ID);
    }

    @Test
    @DisplayName("생성 완료된 그림일기의 공유 링크가 없으면 새로 생성한다")
    void createShareLinkWhenNotExists() {
        ShareLinkCreationResult result = shareLinkService.createOrGet(USER_ID, DIARY_ID);

        assertThat(result.created()).isTrue();
        assertThat(result.shareId()).isNotNull();
        assertThat(result.createdAt()).isNotNull();
        assertThat(shareLinkRepository.findByGenerationId(GENERATION_ID))
                .isPresent()
                .get()
                .extracting(shareLink -> shareLink.getId())
                .isEqualTo(result.shareId());
    }

    @Test
    @DisplayName("공유 링크가 이미 있으면 기존 링크를 반환한다")
    void returnExistingShareLink() {
        ShareLinkCreationResult created = shareLinkService.createOrGet(USER_ID, DIARY_ID);

        ShareLinkCreationResult existing = shareLinkService.createOrGet(USER_ID, DIARY_ID);

        assertThat(existing.created()).isFalse();
        assertThat(existing.shareId()).isEqualTo(created.shareId());
        assertThat(existing.createdAt()).isEqualTo(created.createdAt());
        assertThat(shareLinkRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("그림일기 생성이 처리 중이면 공유 링크를 생성할 수 없다")
    void failToCreateWhileGenerationIsProcessing() {
        changeGenerationStatus("PROCESSING", null, null);

        assertThatThrownBy(() -> shareLinkService.createOrGet(USER_ID, DIARY_ID))
                .isInstanceOf(GenerationInProgressException.class);
        assertThat(shareLinkRepository.count()).isZero();
    }

    @Test
    @DisplayName("그림일기 생성이 실패하면 공유 링크를 생성할 수 없다")
    void failToCreateWhenGenerationFailed() {
        changeGenerationStatus("FAILED", "AI_PROVIDER_ERROR", null);

        assertThatThrownBy(() -> shareLinkService.createOrGet(USER_ID, DIARY_ID))
                .isInstanceOf(ShareGenerationFailedException.class);
        assertThat(shareLinkRepository.count()).isZero();
    }

    @Test
    @DisplayName("다른 사용자의 그림일기에는 공유 링크를 생성할 수 없다")
    void failToCreateForOtherUsersDiary() {
        assertThatThrownBy(() -> shareLinkService.createOrGet(OTHER_USER_ID, DIARY_ID))
                .isInstanceOf(DiaryAccessDeniedException.class);
        assertThat(shareLinkRepository.count()).isZero();
    }

    @Test
    @DisplayName("삭제된 그림일기에는 공유 링크를 생성할 수 없다")
    void failToCreateForDeletedDiary() {
        executeUpdate("UPDATE diaries SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", DIARY_ID);

        assertThatThrownBy(() -> shareLinkService.createOrGet(USER_ID, DIARY_ID))
                .isInstanceOf(DiaryNotFoundException.class);
        assertThat(shareLinkRepository.count()).isZero();
    }

    private void insertGeneration(String status, String errorCode, String imageObjectKey) {
        executeUpdate("""
                INSERT INTO diary_generations (
                    id,
                    diary_id,
                    prompt_id,
                    idempotency_key,
                    request_fingerprint,
                    status,
                    title,
                    image_object_key,
                    error_code,
                    completed_at
                ) VALUES (
                    ?,
                    ?,
                    (SELECT id FROM generation_prompts WHERE image_asset_object_key = ?),
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    CURRENT_TIMESTAMP
                )
                """,
                GENERATION_ID,
                DIARY_ID,
                "references/share-style.png",
                UUID.randomUUID(),
                "a".repeat(64),
                status,
                "친구와 보낸 카페 시간",
                imageObjectKey,
                errorCode
        );
    }

    private void changeGenerationStatus(String status, String errorCode, String imageObjectKey) {
        executeUpdate("""
                UPDATE diary_generations
                SET status = ?, error_code = ?, image_object_key = ?
                WHERE id = ?
                """, status, errorCode, imageObjectKey, GENERATION_ID);
    }

    private void executeUpdate(String statement, Object... parameters) {
        transactionTemplate.executeWithoutResult(status -> {
            Query query = entityManager.createNativeQuery(statement);
            IntStream.range(0, parameters.length)
                    .forEach(index -> query.setParameter(index + 1, parameters[index]));
            query.executeUpdate();
        });
    }
}
