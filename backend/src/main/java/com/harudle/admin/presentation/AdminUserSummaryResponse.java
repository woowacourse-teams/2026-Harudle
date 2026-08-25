package com.harudle.admin.presentation;

import com.harudle.admin.repository.AdminUserSnapshot;
import java.time.Instant;
import java.util.UUID;

record AdminUserSummaryResponse(
        UUID id,
        String name,
        String email,
        AdminUserStatus status,
        Instant createdAt,
        Instant lastLoginAt,
        int remainingGenerationCount
) {

    static AdminUserSummaryResponse from(AdminUserSnapshot user) {
        AdminUserStatus status = user.deletedAt() == null
                ? AdminUserStatus.ACTIVE
                : AdminUserStatus.DELETED;
        return new AdminUserSummaryResponse(
                user.id(),
                user.name(),
                user.email(),
                status,
                user.createdAt(),
                user.lastLoginAt(),
                user.remainingGenerationCount()
        );
    }
}
