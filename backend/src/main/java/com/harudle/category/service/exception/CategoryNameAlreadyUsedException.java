package com.harudle.category.service.exception;

import java.io.Serial;

public final class CategoryNameAlreadyUsedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CategoryNameAlreadyUsedException(Throwable cause) {
        super("이미 사용 중인 카테고리 이름입니다.", cause);
    }
}
