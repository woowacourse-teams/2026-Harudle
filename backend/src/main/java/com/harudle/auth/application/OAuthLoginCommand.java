package com.harudle.auth.application;

import com.harudle.auth.domain.OAuthProvider;
import java.util.Objects;

public record OAuthLoginCommand(
        OAuthProvider provider,
        String providerSubject,
        String providerEmail,
        String displayName
) {
    private static final int PROVIDER_SUBJECT_MAX_LENGTH = 255;
    private static final int EMAIL_MAX_LENGTH = 320;
    private static final int DISPLAY_NAME_MAX_LENGTH = 30;
    private static final String DEFAULT_DISPLAY_NAME = "사용자";

    public OAuthLoginCommand {
        provider = Objects.requireNonNull(provider, "provider는 필수입니다.");
        providerSubject = normalizeRequired(providerSubject, "providerSubject", PROVIDER_SUBJECT_MAX_LENGTH);
        providerEmail = normalizeOptional(providerEmail, "providerEmail", EMAIL_MAX_LENGTH);
        displayName = normalizeDisplayName(displayName);
    }

    private static String normalizeRequired(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + "은(는) 필수입니다."
            );
        }

        String normalized = value.trim();
        validateMaxLength(normalized, fieldName, maxLength);

        return normalized;
    }

    private static String normalizeOptional(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim();
        validateMaxLength(normalized, fieldName, maxLength);

        return normalized;
    }

    private static String normalizeDisplayName(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_DISPLAY_NAME;
        }

        String normalized = value.trim();

        return truncateByCodePoint(normalized, DISPLAY_NAME_MAX_LENGTH);
    }

    private static void validateMaxLength(String value, String fieldName, int maxLength) {
        if (codePointLength(value) <= maxLength) {
            return;
        }

        throw new IllegalArgumentException(
                fieldName + "은(는) " + maxLength + "자를 초과할 수 없습니다."
        );
    }

    private static String truncateByCodePoint(String value, int maxLength) {
        if (codePointLength(value) <= maxLength) {
            return value;
        }

        int endIndex = value.offsetByCodePoints(0, maxLength);
        return value.substring(0, endIndex);
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

}
