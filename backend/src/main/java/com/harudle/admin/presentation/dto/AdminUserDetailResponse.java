package com.harudle.admin.presentation.dto;

import com.harudle.admin.query.AdminUserDetail;
import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.GenerationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminUserDetailResponse(
        UUID id,
        String name,
        AdminUserStatus status,
        Instant createdAt,
        Instant lastLoginAt,
        AdminGenerationUsageResponse generationUsage,
        List<RecentGenerationResponse> recentGenerations
) {

    public static AdminUserDetailResponse from(AdminUserDetail user) {
        AdminUserStatus status = user.deletedAt() == null
                ? AdminUserStatus.ACTIVE
                : AdminUserStatus.DELETED;
        return new AdminUserDetailResponse(
                user.id(),
                user.name(),
                status,
                user.createdAt(),
                user.lastLoginAt(),
                AdminGenerationUsageResponse.from(user.usageDate(), user.usedCount(), user.limitCount()),
                user.recentGenerations().stream()
                        .map(RecentGenerationResponse::from)
                        .toList()
        );
    }

    public record RecentGenerationResponse(
            UUID id,
            Instant requestedAt,
            GenerationStatus status,
            Instant completedAt,
            GenerationErrorCode errorCode
    ) {

        public static RecentGenerationResponse from(AdminUserDetail.RecentGeneration generation) {
            return new RecentGenerationResponse(
                    generation.id(),
                    generation.requestedAt(),
                    generation.status(),
                    generation.completedAt(),
                    generation.errorCode()
            );
        }
    }
}
