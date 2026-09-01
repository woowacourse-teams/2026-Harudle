package com.harudle.auth.presentation;

import com.harudle.auth.application.CurrentUserResult;
import com.harudle.auth.domain.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record CurrentUserResponse(
        UUID id,
        String name,
        String email,
        UserRole role,
        List<String> oauthProviders,
        Instant createdAt
) {

    public static CurrentUserResponse from(CurrentUserResult result) {
        Objects.requireNonNull(result, "result는 필수입니다.");

        return new CurrentUserResponse(
                result.id(),
                result.name(),
                result.email(),
                result.role(),
                List.of(result.oauthProvider().name().toLowerCase(Locale.ROOT)),
                result.createdAt()
        );
    }
}
