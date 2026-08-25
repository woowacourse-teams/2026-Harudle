package com.harudle.admin.presentation;

import com.harudle.admin.repository.AdminUserDetail;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

record AdminUserDetailResponse(UUID id, String name, String email, AdminUserStatus status, Instant createdAt,
                               Instant lastLoginAt, LocalDate usageDate, int usedGenerationCount,
                               int dailyGenerationLimit, int remainingGenerationCount,
                               List<RecentGenerationResponse> recentGenerations) {
    static AdminUserDetailResponse from(AdminUserDetail user) {
        return new AdminUserDetailResponse(user.id(), user.name(), user.email(),
                user.deletedAt() == null ? AdminUserStatus.ACTIVE : AdminUserStatus.DELETED,
                user.createdAt(), user.lastLoginAt(), user.usageDate(), user.usedCount(), user.limitCount(),
                user.limitCount() - user.usedCount(), user.recentGenerations().stream()
                        .map(RecentGenerationResponse::from).toList());
    }
    record RecentGenerationResponse(UUID id, Instant requestedAt, com.harudle.generation.domain.GenerationStatus status,
                                    Instant completedAt, String failureCode) {
        static RecentGenerationResponse from(AdminUserDetail.RecentGeneration generation) {
            return new RecentGenerationResponse(generation.id(), generation.requestedAt(), generation.status(),
                    generation.completedAt(), generation.errorCode());
        }
    }
}
