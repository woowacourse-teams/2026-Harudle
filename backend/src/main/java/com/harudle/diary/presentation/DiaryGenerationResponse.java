package com.harudle.diary.presentation;

import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.UUID;

public record DiaryGenerationResponse(
        UUID id,
        GenerationStatus status,
        String title,
        String imageUrl,
        OffsetDateTime imageUrlExpiresAt,
        OffsetDateTime completedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) GenerationTokenUsage tokenUsage
) {
}
