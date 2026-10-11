package com.harudle.category.service.exception;

import java.io.Serial;

public final class CategoryNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CategoryNotFoundException() {
        super("카테고리를 찾을 수 없습니다.");
    }
}
