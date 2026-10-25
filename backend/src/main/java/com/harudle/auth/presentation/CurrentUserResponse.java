package com.harudle.auth.presentation;

import com.harudle.auth.application.CurrentUserResult;
import com.harudle.auth.domain.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record CurrentUserResponse(
        UUID id,
        String name,
        String nickname,
        @Nullable URI profileImageUrl,
        @Schema(description = "개인 프로필 이미지 URL 만료 시각. 기본 이미지 또는 미배정이면 null",
                type = "string", format = "date-time", nullable = true)
        @Nullable Instant profileImageUrlExpiresAt,
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
                result.nickname(),
                result.profileImageUrl(),
                result.profileImageUrlExpiresAt(),
                result.email(),
                result.role(),
                List.of(result.oauthProvider().name().toLowerCase(Locale.ROOT)),
                result.createdAt()
        );
    }
}
