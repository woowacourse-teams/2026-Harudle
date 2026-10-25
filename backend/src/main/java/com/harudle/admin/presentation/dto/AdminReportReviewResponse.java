package com.harudle.admin.presentation.dto;

import com.harudle.admin.query.AdminReportSnapshot;
import com.harudle.admin.query.AdminReportStatus;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record AdminReportReviewResponse(
        UUID id,
        AdminReportStatus status,
        @Nullable UUID reviewedBy,
        @Nullable Instant reviewedAt,
        @Nullable String reviewReason
) {

    public static AdminReportReviewResponse from(AdminReportSnapshot report) {
        return new AdminReportReviewResponse(report.id(), report.status(), report.reviewedBy(),
                report.reviewedAt(), report.reviewReason());
    }

    @Override
    public String toString() {
        return "AdminReportReviewResponse[id=" + id + ", status=" + status + ", reviewReason=[REDACTED]]";
    }
}
