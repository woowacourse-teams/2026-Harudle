package com.harudle.guest.application;

import java.time.Instant;
import java.util.Objects;

public record IssuedGuestSession(
        String rawToken,
        Instant expiresAt
) {

    public IssuedGuestSession {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("rawToken은 필수입니다.");
        }

        Objects.requireNonNull(expiresAt, "expiresAt은 필수입니다.");
    }
}
