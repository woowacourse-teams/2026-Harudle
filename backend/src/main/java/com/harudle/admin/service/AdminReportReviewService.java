package com.harudle.admin.service;

import com.harudle.admin.query.AdminReportSnapshot;
import com.harudle.admin.query.AdminReportStatus;
import com.harudle.admin.repository.AdminReportRepository;
import com.harudle.admin.service.exception.AdminReportNotFoundException;
import com.harudle.admin.service.exception.AdminReportReviewConflictException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminReportReviewService {

    private final AdminReportRepository reports;
    private final Clock clock;

    public AdminReportReviewService(AdminReportRepository reports, @Qualifier("serviceClock") Clock clock) {
        this.reports = reports;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminReportSnapshot review(UUID reviewerId, UUID reportId, AdminReportReview review) {
        Objects.requireNonNull(reviewerId, "처리자 ID는 필수입니다.");
        Objects.requireNonNull(review, "신고 처리 정보는 필수입니다.");
        var report = reports.findByIdForUpdate(reportId).orElseThrow(AdminReportNotFoundException::new);
        if (report.status() != AdminReportStatus.RECEIVED) {
            if (report.status() == review.status() && review.reason().equals(report.reviewReason())) {
                return report;
            }
            throw new AdminReportReviewConflictException();
        }
        return reports.completeReview(reportId, reviewerId, review, clock.instant());
    }
}
