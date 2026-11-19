package com.harudle.admin.presentation.dto;

import com.harudle.admin.query.AdminMemberStats;

public record AdminStatsResponse(
        long memberCount,
        long todaySignupCount
) {

    public static AdminStatsResponse from(AdminMemberStats stats) {
        return new AdminStatsResponse(stats.memberCount(), stats.todaySignupCount());
    }
}
