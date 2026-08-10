package com.harudle.common.error;

public record FieldValidationError(
        String field,
        String reason
) {
}
