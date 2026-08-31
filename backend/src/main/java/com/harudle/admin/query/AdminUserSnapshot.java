package com.harudle.admin.query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AdminUserSnapshot(
        UUID id,
        String name,
        Instant createdAt,
        Instant deletedAt,
        Instant lastLoginAt,
        LocalDate usageDate,
        int usedCount,
        int limitCount
) {
}
