package com.harudle.admin.presentation.dto;

import com.harudle.admin.query.AdminReportStatus;
import com.harudle.admin.service.AdminReportReview;
import com.harudle.common.validation.CodePointLength;
import jakarta.validation.constraints.NotNull;

public record AdminReportReviewRequest(
        @NotNull AdminReportStatus status,
        @NotNull @CodePointLength(min = 1, max = 500) String reason
) {

    public AdminReportReview toReview() {
        return new AdminReportReview(status, reason);
    }

    @Override
    public String toString() {
        return "AdminReportReviewRequest[status=" + status + ", reason=[REDACTED]]";
    }
}
