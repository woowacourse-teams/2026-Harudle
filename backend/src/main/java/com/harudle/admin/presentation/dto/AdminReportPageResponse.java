package com.harudle.admin.presentation.dto;

import com.harudle.admin.query.AdminReportPage;
import com.harudle.admin.query.AdminReportSummary;
import java.util.List;

public record AdminReportPageResponse(
        List<AdminReportSummary> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {

    public static AdminReportPageResponse from(AdminReportPage result, int page, int size) {
        int totalPages = (int) Math.ceil((double) result.totalElements() / size);
        return new AdminReportPageResponse(result.content(), page, size, result.totalElements(),
                totalPages, page + 1 < totalPages);
    }
}
