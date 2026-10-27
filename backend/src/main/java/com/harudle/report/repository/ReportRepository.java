package com.harudle.report.repository;

import com.harudle.report.domain.ReportTargetType;
import java.util.Optional;
import java.util.UUID;

public interface ReportRepository {

    boolean isActiveMember(UUID userId);

    Optional<UUID> findReceiptIdByReporterAndTarget(UUID reporterId, ReportTargetType targetType, UUID targetId);

    /** Returns false for a report already submitted by the same reporter for the same target. */
    boolean insert(ReportSnapshot report);
}
