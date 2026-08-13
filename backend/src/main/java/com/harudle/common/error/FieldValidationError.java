package com.harudle.common.error;

record FieldValidationError(
        String field,
        String reason
) {
}
