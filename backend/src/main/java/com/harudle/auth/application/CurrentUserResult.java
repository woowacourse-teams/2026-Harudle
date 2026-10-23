package com.harudle.auth.application;

import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.domain.User;
import com.harudle.auth.domain.UserRole;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CurrentUserResult(
        UUID id,
        String name,
        String nickname,
        URI profileImageUrl,
        String email,
        UserRole role,
        OAuthProvider oauthProvider,
        Instant createdAt
) {

    public CurrentUserResult {
        id = Objects.requireNonNull(id, "id는 필수입니다.");
        name = Objects.requireNonNull(name, "name은 필수입니다.");
        role = Objects.requireNonNull(role, "role은 필수입니다.");
        oauthProvider = Objects.requireNonNull(oauthProvider, "oauthProvider는 필수입니다.");
        createdAt = Objects.requireNonNull(createdAt, "createdAt은 필수입니다.");
    }

    public static CurrentUserResult from(User user, OAuthAccount oauthAccount, URI profileImageUrl) {
        Objects.requireNonNull(user, "user는 필수입니다.");
        Objects.requireNonNull(oauthAccount, "oauthAccount는 필수입니다.");

        return new CurrentUserResult(
                user.getId(),
                user.getName(),
                user.getNickname(),
                profileImageUrl,
                user.getPrimaryEmail(),
                user.getRole(),
                oauthAccount.getProvider(),
                user.getCreatedAt()
        );
    }

}
