package com.harudle.common.validation;

public final class TextValidator {

    private TextValidator() {
    }

    public static String normalizeRequired(String value, String errorMessage) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(errorMessage);
        }
        return value.strip();
    }
}
