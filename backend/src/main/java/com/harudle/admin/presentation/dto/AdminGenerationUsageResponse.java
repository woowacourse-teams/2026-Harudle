package com.harudle.admin.presentation.dto;

import com.harudle.admin.repository.AdminUserSnapshot;
import java.time.LocalDate;

public record AdminGenerationUsageResponse(
        LocalDate usageDate,
        int usedCount,
        int limitCount,
        int remainingCount
) {

    public static AdminGenerationUsageResponse from(AdminUserSnapshot user) {
        return new AdminGenerationUsageResponse(
                user.usageDate(),
                user.usedCount(),
                user.limitCount(),
                user.limitCount() - user.usedCount()
        );
    }
}
