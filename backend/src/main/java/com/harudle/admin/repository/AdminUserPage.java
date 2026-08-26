package com.harudle.admin.repository;

import java.util.List;

public record AdminUserPage(
        List<AdminUserSnapshot> content,
        long totalElements
) {
}
