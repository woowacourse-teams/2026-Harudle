package com.harudle.admin.query;

import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.domain.GenerationStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AdminUserDetail(
        UUID id,
        String name,
        Instant createdAt,
        Instant deletedAt,
        Instant lastLoginAt,
        LocalDate usageDate,
        int usedCount,
        int limitCount,
        List<RecentGeneration> recentGenerations
) {

    public AdminUserDetail {
        recentGenerations = List.copyOf(recentGenerations);
    }

    public record RecentGeneration(
            UUID id,
            Instant requestedAt,
            GenerationStatus status,
            Instant completedAt,
            GenerationErrorCode errorCode
    ) {
    }
}
