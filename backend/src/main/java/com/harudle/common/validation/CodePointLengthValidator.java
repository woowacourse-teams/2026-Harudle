package com.harudle.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class CodePointLengthValidator implements ConstraintValidator<CodePointLength, CharSequence> {

    private int min;
    private int max;

    @Override
    public void initialize(CodePointLength constraintAnnotation) {
        min = constraintAnnotation.min();
        max = constraintAnnotation.max();
        if (min < 0 || max < min) {
            throw new IllegalArgumentException("코드 포인트 길이 범위가 올바르지 않습니다.");
        }
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        String normalizedValue = value.toString().strip();
        int length = normalizedValue.codePointCount(0, normalizedValue.length());
        return length >= min && length <= max;
    }
}
