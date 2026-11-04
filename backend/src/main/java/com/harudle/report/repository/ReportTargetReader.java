package com.harudle.report.repository;

import com.harudle.report.domain.ReportTargetType;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface ReportTargetReader {

    /** 신고 가능한 대상과 작성자를 조회한다. 탈퇴한 회원의 댓글은 작성자가 없을 수 있다. */
    Optional<ReportTarget> findVisibleTarget(UUID actorId, ReportTargetType targetType, UUID targetId);

    record ReportTarget(@Nullable UUID authorId) {}
}
