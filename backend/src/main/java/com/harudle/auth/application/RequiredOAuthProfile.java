package com.harudle.auth.application;

public record RequiredOAuthProfile(
        String email,
        String displayName
) {

    public RequiredOAuthProfile {
        email = normalizeOptional(email);
        displayName = requireValue(displayName, "닉네임");
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private static String requireValue(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new RequiredOAuthProfileException(fieldName + "이 필요합니다.");
        }

        return value;
    }

}
