package com.harudle.diary.service.dto;

import com.harudle.generation.domain.GenerationStatus;
import java.time.Instant;
import java.util.UUID;

public record DiaryGenerationResult(
        UUID id,
        GenerationStatus status,
        String title,
        String imageObjectKey,
        Instant completedAt
) {
}
