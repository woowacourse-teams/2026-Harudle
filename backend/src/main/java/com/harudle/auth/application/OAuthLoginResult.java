package com.harudle.auth.application;

import java.util.Objects;
import java.util.UUID;

public record OAuthLoginResult(UUID userId) {

    public OAuthLoginResult {
        userId = Objects.requireNonNull(userId, "userId는 필수입니다.");
    }

}
