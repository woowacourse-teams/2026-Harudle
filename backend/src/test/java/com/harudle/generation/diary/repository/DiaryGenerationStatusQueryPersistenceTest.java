package com.harudle.generation.diary.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.support.ServiceIntegrationTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class DiaryGenerationStatusQueryPersistenceTest extends ServiceIntegrationTestSupport {

    @Autowired private DiaryGenerationQueryRepository repository;
    @Autowired private JdbcTemplate jdbc;
    private UUID userId;
    private Long promptId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, name) VALUES (?, '생성 상태 테스트')", userId);
        promptId = jdbc.queryForObject("""
                INSERT INTO generation_prompts (storyboard_prompt_text, image_style_prompt_text, image_asset_object_key)
                VALUES ('스토리보드', '이미지 스타일', 'references/status.png') RETURNING id
                """, Long.class);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        jdbc.update("DELETE FROM generation_prompts WHERE id = ?", promptId);
    }

    @ParameterizedTest
    @EnumSource(GenerationStatus.class)
    @DisplayName("본인의 상태를 잠금 없이 읽고 실패로 숨겨진 일기의 상태도 반환한다")
    void readOwnedGenerationIncludingHiddenFailure(GenerationStatus status) {
        UUID generationId = insertGeneration(status, status == GenerationStatus.FAILED);

        DiaryGenerationStatusSnapshot first = repository.findStatusSnapshotByIdAndUserId(generationId, userId).orElseThrow();
        DiaryGenerationStatusSnapshot second = repository.findStatusSnapshotByIdAndUserId(generationId, userId).orElseThrow();

        assertThat(first.id()).isEqualTo(generationId);
        assertThat(first.status()).isEqualTo(status);
        assertThat(second).isEqualTo(first);
        assertThat(first.errorCode()).isEqualTo(status == GenerationStatus.FAILED
                ? com.harudle.generation.diary.domain.GenerationErrorCode.AI_PROVIDER_TIMEOUT : null);
        assertThat(jdbc.queryForObject("SELECT status FROM diary_generations WHERE id = ?", String.class, generationId))
                .isEqualTo(status.name());
    }

    @Test
    @DisplayName("다른 사용자와 존재하지 않는 생성 작업은 조회되지 않는다")
    void excludeUnownedAndMissingGeneration() {
        UUID generationId = insertGeneration(GenerationStatus.PENDING, false);

        assertThat(repository.findStatusSnapshotByIdAndUserId(generationId, UUID.randomUUID())).isEmpty();
        assertThat(repository.findStatusSnapshotByIdAndUserId(UUID.randomUUID(), userId)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = GenerationStatus.class, names = {"PENDING", "PROCESSING", "SUCCEEDED"})
    @DisplayName("사용자가 삭제한 대기 또는 처리 중 또는 성공한 일기는 상태 조회에서 제외한다")
    void excludeDeletedNonFailedGeneration(GenerationStatus status) {
        UUID generationId = insertGeneration(status, true);

        assertThat(repository.findStatusSnapshotByIdAndUserId(generationId, userId)).isEmpty();
    }

    private UUID insertGeneration(GenerationStatus status, boolean deleted) {
        UUID diaryId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO diaries (id, user_id, diary_date, source_text, deleted_at)
                VALUES (?, ?, DATE '2026-10-20', '생성 상태 확인', CASE WHEN ? THEN now() ELSE NULL END)
                """, diaryId, userId, deleted);
        jdbc.update("""
                INSERT INTO diary_generations (
                    id, diary_id, prompt_id, idempotency_key, request_fingerprint, status, image_object_key, error_code
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, generationId, diaryId, promptId, UUID.randomUUID(), "a".repeat(64), status.name(),
                status == GenerationStatus.SUCCEEDED ? "generated/status.png" : null,
                status == GenerationStatus.FAILED ? "AI_PROVIDER_TIMEOUT" : null);
        return generationId;
    }
}
