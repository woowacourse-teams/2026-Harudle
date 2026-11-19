package com.harudle.admin.presentation.dto;

import com.harudle.admin.query.AdminUserSnapshot;
import java.time.Instant;
import java.util.UUID;

public record AdminUserSummaryResponse(
        UUID id,
        String name,
        String nickname,
        AdminUserStatus status,
        Instant createdAt,
        Instant lastLoginAt,
        AdminGenerationUsageResponse generationUsage,
        long generatedDiaryCount
) {

    public static AdminUserSummaryResponse from(AdminUserSnapshot user) {
        AdminUserStatus status = user.deletedAt() == null
                ? AdminUserStatus.ACTIVE
                : AdminUserStatus.DELETED;
        return new AdminUserSummaryResponse(
                user.id(),
                user.name(),
                user.nickname(),
                status,
                user.createdAt(),
                user.lastLoginAt(),
                AdminGenerationUsageResponse.from(user),
                user.generatedDiaryCount()
        );
    }
}
