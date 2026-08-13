package com.harudle.auth.application;

public record RequiredOAuthProfile(
        String email,
        String displayName
) {

    public RequiredOAuthProfile {
        email = requireValue(email, "이메일");
        displayName = requireValue(displayName, "닉네임");
    }

    private static String requireValue(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new RequiredOAuthProfileException(fieldName + "이 필요합니다.");
        }

        return value;
    }

}
