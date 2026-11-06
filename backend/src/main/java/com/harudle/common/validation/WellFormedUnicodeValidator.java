package com.harudle.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class WellFormedUnicodeValidator implements ConstraintValidator<WellFormedUnicode, CharSequence> {

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        return value == null || UnicodeText.isWellFormed(value);
    }
}
