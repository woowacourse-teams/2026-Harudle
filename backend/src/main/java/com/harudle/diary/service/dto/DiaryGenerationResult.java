package com.harudle.diary.service.dto;

import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
import java.time.Instant;
import java.util.UUID;

public record DiaryGenerationResult(
        UUID id,
        GenerationStatus status,
        String title,
        String imageObjectKey,
        Instant completedAt,
        GenerationTokenUsage tokenUsage
) {
    public DiaryGenerationResult(
            UUID id,
            GenerationStatus status,
            String title,
            String imageObjectKey,
            Instant completedAt
    ) {
        this(id, status, title, imageObjectKey, completedAt, null);
    }
}
