package com.harudle.category.service.exception;

import java.io.Serial;

public final class CategoryInactiveException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CategoryInactiveException() {
        super("비활성 카테고리에는 게시할 수 없습니다.");
    }
}
