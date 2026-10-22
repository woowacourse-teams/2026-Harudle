package com.harudle.report.service;

import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.report.domain.InvalidReportException;
import com.harudle.report.domain.ReportSubmission;
import com.harudle.report.repository.ReportRepository;
import com.harudle.report.repository.ReportSnapshot;
import com.harudle.report.repository.ReportTargetReader;
import com.harudle.report.service.exception.ReportTargetNotFoundException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {

    private final ReportRepository reports;
    private final ReportTargetReader targets;
    private final Clock clock;

    public ReportService(ReportRepository reports, ReportTargetReader targets,
            @Qualifier("serviceClock") Clock clock) {
        this.reports = reports;
        this.targets = targets;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReportReceipt submit(UUID reporterId, ReportSubmission submission) {
        if (reporterId == null || !reports.isActiveMember(reporterId)) {
            throw new AuthenticationRequiredException();
        }
        var existing = reports.findByReporterAndTarget(reporterId, submission.targetType(), submission.targetId());
        if (existing.isPresent()) {
            return new ReportReceipt(existing.get().id(), true);
        }
        UUID targetUserId = targets
                .findVisibleTargetAuthorId(reporterId, submission.targetType(), submission.targetId())
                .orElseThrow(ReportTargetNotFoundException::new);
        if (reporterId.equals(targetUserId)) {
            throw new InvalidReportException();
        }
        ReportSnapshot report = new ReportSnapshot(UUID.randomUUID(), reporterId, submission.targetType(),
                submission.targetId(), targetUserId, submission.reason(), submission.description(), clock.instant());
        if (reports.insert(report)) {
            return new ReportReceipt(report.id(), false);
        }
        // ON CONFLICT waits for the winner; a new READ COMMITTED statement reads that committed receipt.
        return reports.findByReporterAndTarget(reporterId, submission.targetType(), submission.targetId())
                .map(saved -> new ReportReceipt(saved.id(), true))
                .orElseThrow(() -> new IllegalStateException("신고 접수 결과를 확인할 수 없습니다."));
    }
}
