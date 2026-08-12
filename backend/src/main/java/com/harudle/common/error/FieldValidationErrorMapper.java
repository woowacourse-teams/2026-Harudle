package com.harudle.common.error;

import java.util.List;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

final class FieldValidationErrorMapper {

    private static final String DEFAULT_REASON = "요청 값이 올바르지 않습니다.";

    private FieldValidationErrorMapper() {
    }

    static List<FieldValidationError> from(BindingResult bindingResult) {
        return bindingResult.getFieldErrors().stream()
                .map(FieldValidationErrorMapper::from)
                .toList();
    }

    private static FieldValidationError from(FieldError error) {
        String reason = error.getDefaultMessage();
        if (reason == null) {
            return new FieldValidationError(error.getField(), DEFAULT_REASON);
        }
        return new FieldValidationError(error.getField(), reason);
    }
}
