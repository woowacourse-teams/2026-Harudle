package com.harudle.category.presentation;

import com.harudle.category.query.CategorySnapshot;

public record CategoryResponse(
        long id,
        String name,
        int sortOrder,
        boolean active
) {

    public static CategoryResponse from(CategorySnapshot category) {
        return new CategoryResponse(category.id(), category.name(), category.sortOrder(), category.active());
    }
}
