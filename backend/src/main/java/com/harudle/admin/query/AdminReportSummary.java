package com.harudle.admin.query;

import com.harudle.report.domain.ReportReason;
import com.harudle.report.domain.ReportTargetType;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record AdminReportSummary(
        UUID id,
        @Nullable UUID reporterId,
        ReportTargetType targetType,
        @Nullable UUID targetId,
        @Nullable UUID targetUserId,
        ReportReason reason,
        AdminReportStatus status,
        Instant createdAt,
        @Nullable Instant reviewedAt
) {
}
