package com.harudle.diary.presentation;

import com.harudle.generation.diary.domain.GenerationStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record DiaryGenerationResponse(
        UUID id,
        GenerationStatus status,
        String title,
        String imageUrl,
        OffsetDateTime imageUrlExpiresAt,
        OffsetDateTime completedAt
) {
}
