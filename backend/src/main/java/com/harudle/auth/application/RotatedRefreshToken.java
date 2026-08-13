package com.harudle.auth.application;

import java.util.Objects;
import java.util.UUID;

public record RotatedRefreshToken(
        UUID userId,
        IssuedRefreshToken issuedRefreshToken
) {

    public RotatedRefreshToken {
        userId = Objects.requireNonNull(userId, "userId는 필수입니다.");
        issuedRefreshToken = Objects.requireNonNull(
                issuedRefreshToken,
                "issuedRefreshToken은 필수입니다."
        );
    }

}
