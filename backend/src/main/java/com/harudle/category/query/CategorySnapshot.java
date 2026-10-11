package com.harudle.category.query;

public record CategorySnapshot(
        long id,
        String name,
        int sortOrder,
        boolean active
) {
}
