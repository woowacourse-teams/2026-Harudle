package com.harudle.admin.presentation.dto;

import com.harudle.admin.query.AdminUserSnapshot;
import java.time.LocalDate;

public record AdminGenerationUsageResponse(
        LocalDate usageDate,
        int usedCount,
        int limitCount,
        int remainingCount
) {

    public static AdminGenerationUsageResponse from(AdminUserSnapshot user) {
        return from(user.usageDate(), user.usedCount(), user.limitCount());
    }

    static AdminGenerationUsageResponse from(LocalDate usageDate, int usedCount, int limitCount) {
        return new AdminGenerationUsageResponse(
                usageDate,
                usedCount,
                limitCount,
                limitCount - usedCount
        );
    }
}
