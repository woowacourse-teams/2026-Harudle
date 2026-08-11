package com.harudle.common.error;

import java.util.List;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

final class FieldValidationErrorMapper {

    private FieldValidationErrorMapper() {
    }

    static List<FieldValidationError> from(BindingResult bindingResult) {
        return bindingResult.getFieldErrors().stream()
                .map(FieldValidationErrorMapper::from)
                .toList();
    }

    private static FieldValidationError from(FieldError error) {
        return new FieldValidationError(error.getField(), error.getDefaultMessage());
    }
}
