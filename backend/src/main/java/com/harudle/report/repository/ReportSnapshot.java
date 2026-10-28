package com.harudle.report.repository;

import com.harudle.report.domain.ReportReason;
import com.harudle.report.domain.ReportTargetType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ReportSnapshot(
        UUID id,
        UUID reporterId,
        ReportTargetType targetType,
        UUID targetId,
        UUID targetUserId,
        ReportReason reason,
        String description,
        Instant createdAt
) {
    public ReportSnapshot {
        Objects.requireNonNull(id);
        Objects.requireNonNull(reporterId);
        Objects.requireNonNull(targetType);
        Objects.requireNonNull(targetId);
        Objects.requireNonNull(targetUserId);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(description);
        Objects.requireNonNull(createdAt);
    }

    @Override
    public String toString() {
        return "ReportSnapshot[id=" + id + ", reporterId=" + reporterId + ", targetType=" + targetType
                + ", targetId=" + targetId + ", targetUserId=" + targetUserId + ", reason=" + reason
                + ", description=[REDACTED], createdAt=" + createdAt + "]";
    }
}
