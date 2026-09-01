package com.harudle.diary.service.dto;

import com.harudle.generation.diary.domain.GenerationStatus;
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
