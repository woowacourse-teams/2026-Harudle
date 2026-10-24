package com.harudle.admin.query;

import java.util.List;

public record AdminReportPage(List<AdminReportSummary> content, long totalElements) {

    public AdminReportPage {
        content = List.copyOf(content);
    }
}
