package com.harudle.generation.repository;

import com.harudle.generation.domain.GenerationStatus;
import java.time.Instant;
import java.util.UUID;

public record ComicGenerationSnapshot(
        UUID id,
        UUID diaryId,
        GenerationStatus status,
        String title,
        String imageObjectKey,
        Instant completedAt
) {
}
