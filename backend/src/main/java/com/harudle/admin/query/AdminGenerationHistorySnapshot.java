package com.harudle.admin.query;

import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.domain.GenerationStatus;
import java.time.Instant;
import java.util.UUID;

public record AdminGenerationHistorySnapshot(
        UUID id,
        UUID userId,
        String userName,
        Instant requestedAt,
        GenerationStatus status,
        Instant completedAt,
        GenerationErrorCode errorCode
) {
}
