package com.harudle.auth.presentation;

import com.harudle.auth.application.CurrentUserResult;
import com.harudle.auth.domain.OAuthProvider;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CurrentUserResponse(
        UUID id,
        String name,
        String email,
        OAuthProvider oauthProvider,
        Instant createdAt
) {

    public static CurrentUserResponse from(CurrentUserResult result) {
        Objects.requireNonNull(result, "result는 필수입니다.");

        return new CurrentUserResponse(
                result.id(),
                result.name(),
                result.email(),
                result.oauthProvider(),
                result.createdAt()
        );
    }
}
