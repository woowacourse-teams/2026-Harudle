package com.harudle.admin.query;

import java.util.List;

public record AdminUserPage(
        List<AdminUserSnapshot> content,
        long totalElements
) {
}
