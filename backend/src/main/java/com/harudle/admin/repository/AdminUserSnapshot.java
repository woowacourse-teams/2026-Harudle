package com.harudle.admin.repository;

import java.time.Instant;
import java.util.UUID;

public record AdminUserSnapshot(
        UUID id,
        String name,
        String email,
        Instant createdAt,
        Instant deletedAt,
        Instant lastLoginAt,
        int remainingGenerationCount
) {
}
