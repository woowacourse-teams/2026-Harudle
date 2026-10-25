package com.harudle.admin.service;

import com.harudle.admin.query.AdminReportDetail;
import com.harudle.admin.query.AdminReportPage;
import com.harudle.admin.query.AdminReportStatus;
import com.harudle.admin.repository.AdminReportRepository;
import com.harudle.admin.service.exception.AdminReportNotFoundException;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminReportQueryService {

    private final AdminReportRepository reports;

    public AdminReportQueryService(AdminReportRepository reports) {
        this.reports = reports;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminReportPage search(AdminReportStatus status, int page, int size) {
        return reports.search(status, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminReportDetail findDetail(UUID id) {
        var report = reports.findById(id).orElseThrow(AdminReportNotFoundException::new);
        return new AdminReportDetail(report, reports.findTarget(report.targetType(), report.targetId()));
    }
}
