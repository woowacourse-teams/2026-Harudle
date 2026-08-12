package com.harudle.auth.application;

import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final RefreshTokenService refreshTokenService;
    private final AccessTokenService accessTokenService;

    public AuthService(
            RefreshTokenService refreshTokenService,
            AccessTokenService accessTokenService
    ) {
        this.refreshTokenService = refreshTokenService;
        this.accessTokenService = accessTokenService;
    }

    @Transactional
    public RefreshedTokens refresh(String rawRefreshToken, Instant now) {
        Objects.requireNonNull(rawRefreshToken, "rawRefreshToken은 필수입니다.");
        Objects.requireNonNull(now, "now는 필수입니다.");

        RotatedRefreshToken rotatedRefreshToken = refreshTokenService.rotate(
                rawRefreshToken,
                now
        );
        IssuedAccessToken issuedAccessToken = accessTokenService.issue(
                rotatedRefreshToken.userId(),
                now
        );

        return new RefreshedTokens(
                issuedAccessToken,
                rotatedRefreshToken.issuedRefreshToken()
        );
    }
}
