package com.harudle.report.repository;

import com.harudle.report.domain.ReportTargetType;
import java.util.Optional;
import java.util.UUID;

public interface ReportTargetReader {

    /** Reads the author of a target visible to the actor at validation time, without copying its content. */
    Optional<UUID> findVisibleTargetAuthorId(UUID actorId, ReportTargetType targetType, UUID targetId);
}
