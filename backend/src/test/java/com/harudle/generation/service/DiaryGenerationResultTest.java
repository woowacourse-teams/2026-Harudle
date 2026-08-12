package com.harudle.generation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.service.dto.DiaryGenerationResult;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiaryGenerationResultTest {

    @Test
    @DisplayName("성공한 그림일기 생성 결과의 문자열 앞뒤 공백을 제거한다")
    void createDiaryGenerationResult() {
        UUID generationId = UUID.randomUUID();
        Instant completedAt = Instant.parse("2026-08-10T10:00:00Z");

        DiaryGenerationResult result = new DiaryGenerationResult(
                generationId,
                GenerationStatus.SUCCEEDED,
                " 친구와 보낸 하루 ",
                " generated/diary-image.png ",
                completedAt,
                true
        );

        assertThat(result.generationId()).isEqualTo(generationId);
        assertThat(result.status()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(result.title()).isEqualTo("친구와 보낸 하루");
        assertThat(result.imageObjectKey()).isEqualTo("generated/diary-image.png");
        assertThat(result.completedAt()).isEqualTo(completedAt);
        assertThat(result.newlyCreated()).isTrue();
    }

    @Test
    @DisplayName("생성 ID가 없으면 그림일기 생성 결과를 만들 수 없다")
    void rejectNullGenerationId() {
        assertThatThrownBy(() -> createResult(null, GenerationStatus.SUCCEEDED, "제목", "image.png", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("생성 ID");
    }

    @Test
    @DisplayName("성공 상태가 아니면 그림일기 생성 결과를 만들 수 없다")
    void rejectNonSucceededStatus() {
        assertThatThrownBy(() -> createResult(
                UUID.randomUUID(),
                GenerationStatus.PROCESSING,
                "제목",
                "image.png",
                Instant.now()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("성공한 생성 작업");
    }

    @Test
    @DisplayName("제목이 비어 있으면 그림일기 생성 결과를 만들 수 없다")
    void rejectBlankTitle() {
        assertThatThrownBy(() -> createResult(
                UUID.randomUUID(),
                GenerationStatus.SUCCEEDED,
                " ",
                "image.png",
                Instant.now()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("제목");
    }

    @Test
    @DisplayName("이미지 Object Key가 비어 있으면 그림일기 생성 결과를 만들 수 없다")
    void rejectBlankImageObjectKey() {
        assertThatThrownBy(() -> createResult(
                UUID.randomUUID(),
                GenerationStatus.SUCCEEDED,
                "제목",
                " ",
                Instant.now()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Object Key");
    }

    @Test
    @DisplayName("이미지 Object Key가 UTF-8 기준 1,024바이트를 초과하면 결과를 만들 수 없다")
    void rejectLongImageObjectKey() {
        assertThatThrownBy(() -> createResult(
                UUID.randomUUID(),
                GenerationStatus.SUCCEEDED,
                "제목",
                "가".repeat(342),
                Instant.now()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1,024바이트");
    }

    @Test
    @DisplayName("완료 시각이 없으면 그림일기 생성 결과를 만들 수 없다")
    void rejectNullCompletedAt() {
        assertThatThrownBy(() -> createResult(
                UUID.randomUUID(),
                GenerationStatus.SUCCEEDED,
                "제목",
                "image.png",
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("완료 시각");
    }

    private DiaryGenerationResult createResult(
            UUID generationId,
            GenerationStatus status,
            String title,
            String imageObjectKey,
            Instant completedAt
    ) {
        return new DiaryGenerationResult(
                generationId,
                status,
                title,
                imageObjectKey,
                completedAt,
                true
        );
    }
}
