package com.harudle.auth.presentation;

import com.harudle.auth.application.IssuedAccessToken;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record RefreshTokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {

    private static final String BEARER = "Bearer";

    public RefreshTokenResponse {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken은 필수입니다.");
        }
        if (!BEARER.equals(tokenType)) {
            throw new IllegalArgumentException("tokenType은 Bearer여야 합니다.");
        }
        if (expiresIn <= 0) {
            throw new IllegalArgumentException("expiresIn은 양수여야 합니다.");
        }
    }

    public static RefreshTokenResponse from(IssuedAccessToken issuedAccessToken, Instant now) {
        Objects.requireNonNull(issuedAccessToken, "issuedAccessToken은 필수입니다.");
        Objects.requireNonNull(now, "now는 필수입니다.");

        long expiresIn = Duration.between(now, issuedAccessToken.expiresAt()).toSeconds();
        return new RefreshTokenResponse(
                issuedAccessToken.accessToken(),
                BEARER,
                expiresIn
        );
    }
}
