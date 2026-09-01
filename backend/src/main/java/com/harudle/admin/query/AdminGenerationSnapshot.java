package com.harudle.admin.query;

import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.GenerationStatus;
import java.time.Instant;
import java.util.UUID;

public record AdminGenerationSnapshot(
        UUID id,
        Instant requestedAt,
        GenerationStatus status,
        Instant completedAt,
        GenerationErrorCode errorCode
) {
}
