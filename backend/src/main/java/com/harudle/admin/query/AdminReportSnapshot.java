package com.harudle.admin.query;

import com.harudle.report.domain.ReportReason;
import com.harudle.report.domain.ReportTargetType;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record AdminReportSnapshot(
        UUID id,
        @Nullable UUID reporterId,
        ReportTargetType targetType,
        UUID targetId,
        UUID targetUserId,
        ReportReason reason,
        String description,
        AdminReportStatus status,
        Instant createdAt,
        @Nullable UUID reviewedBy,
        @Nullable Instant reviewedAt,
        @Nullable String reviewReason
) {

    @Override
    public String toString() {
        return "AdminReportSnapshot[id=" + id + ", status=" + status + ", description=[REDACTED], "
                + "reviewReason=[REDACTED]]";
    }
}
