package com.harudle.category.domain.exception;

import java.io.Serial;

public final class InvalidCategoryException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String field;

    public InvalidCategoryException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
