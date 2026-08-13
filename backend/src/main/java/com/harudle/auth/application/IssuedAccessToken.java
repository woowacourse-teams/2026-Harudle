package com.harudle.auth.application;

import java.time.Instant;
import java.util.Objects;

public record IssuedAccessToken(String accessToken, Instant expiresAt) {

    public IssuedAccessToken {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken은 필수입니다.");
        }

        Objects.requireNonNull(expiresAt, "expiresAt은 필수입니다.");
    }

}
