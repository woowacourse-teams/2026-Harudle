package com.harudle.generation.diary.repository;

import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
import java.time.Instant;
import java.util.UUID;

public record DiaryGenerationSnapshot(
        UUID id,
        UUID diaryId,
        GenerationStatus status,
        String title,
        String imageObjectKey,
        Instant completedAt,
        GenerationTokenUsage tokenUsage
) {
    public DiaryGenerationSnapshot(
            UUID id,
            UUID diaryId,
            GenerationStatus status,
            String title,
            String imageObjectKey,
            Instant completedAt
    ) {
        this(id, diaryId, status, title, imageObjectKey, completedAt, null);
    }
}
