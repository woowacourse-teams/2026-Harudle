package com.harudle.generation.service.dto;

import com.harudle.common.validation.TextValidator;
import com.harudle.generation.domain.ImageObjectKeyPolicy;
import java.time.Instant;
import java.util.UUID;

public record CompletedComicGeneration(
        UUID generationId,
        String title,
        String imageObjectKey,
        Instant completedAt
) {

    public CompletedComicGeneration {
        validateGenerationId(generationId);
        title = TextValidator.normalizeRequired(title, "생성 결과 제목은 필수입니다.");
        imageObjectKey = ImageObjectKeyPolicy.normalizeRequired(
                imageObjectKey,
                "생성 이미지 Object Key"
        );
        validateCompletedAt(completedAt);
    }

    private static void validateGenerationId(UUID generationId) {
        if (generationId == null) {
            throw new IllegalArgumentException("생성 ID는 필수입니다.");
        }
    }

    private static void validateCompletedAt(Instant completedAt) {
        if (completedAt == null) {
            throw new IllegalArgumentException("생성 완료 시각은 필수입니다.");
        }
    }
}
