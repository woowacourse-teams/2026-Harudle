package com.harudle.admin.query;

import java.util.List;

public record AdminGenerationHistoryPage(
        List<AdminGenerationHistorySnapshot> content,
        long totalElements
) {

    public AdminGenerationHistoryPage {
        content = List.copyOf(content);
    }
}
