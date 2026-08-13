package com.harudle.auth.application;

import java.util.Objects;

public record RefreshedTokens(
        IssuedAccessToken accessToken,
        IssuedRefreshToken refreshToken
) {

    public RefreshedTokens {
        accessToken = Objects.requireNonNull(accessToken, "accessToken은 필수입니다.");
        refreshToken = Objects.requireNonNull(refreshToken, "refreshToken은 필수입니다.");
    }
}
