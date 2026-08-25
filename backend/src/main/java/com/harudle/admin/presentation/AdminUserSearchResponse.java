package com.harudle.admin.presentation;

import com.harudle.admin.repository.AdminUserPage;
import java.util.List;

record AdminUserSearchResponse(
        List<AdminUserSummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {

    static AdminUserSearchResponse from(AdminUserPage result, int page, int size) {
        int totalPages = (int) Math.ceil((double) result.totalElements() / size);
        return new AdminUserSearchResponse(
                result.content().stream()
                        .map(AdminUserSummaryResponse::from)
                        .toList(),
                page,
                size,
                result.totalElements(),
                totalPages,
                page + 1 < totalPages
        );
    }
}
