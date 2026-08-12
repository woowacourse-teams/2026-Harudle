package com.harudle.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuthServiceTest {

    private static final Instant REFRESHED_AT = Instant.parse("2026-08-12T10:00:00Z");

    private RefreshTokenService refreshTokenService;
    private AccessTokenService accessTokenService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        refreshTokenService = mock(RefreshTokenService.class);
        accessTokenService = mock(AccessTokenService.class);
        authService = new AuthService(refreshTokenService, accessTokenService);
    }

    @Test
    @DisplayName("Refresh Token을 Rotation하고 같은 사용자에게 Access Token을 발급한다")
    void refreshesTokens() {
        String rawRefreshToken = "raw-refresh-token";
        UUID userId = UUID.randomUUID();
        IssuedRefreshToken issuedRefreshToken = new IssuedRefreshToken(
                "rotated-refresh-token",
                REFRESHED_AT.plusSeconds(1_209_600)
        );
        RotatedRefreshToken rotatedRefreshToken = new RotatedRefreshToken(
                userId,
                issuedRefreshToken
        );
        IssuedAccessToken issuedAccessToken = new IssuedAccessToken(
                "access-token",
                REFRESHED_AT.plusSeconds(1_800)
        );
        when(refreshTokenService.rotate(rawRefreshToken, REFRESHED_AT))
                .thenReturn(rotatedRefreshToken);
        when(accessTokenService.issue(userId, REFRESHED_AT))
                .thenReturn(issuedAccessToken);

        RefreshedTokens result = authService.refresh(rawRefreshToken, REFRESHED_AT);

        assertThat(result.accessToken()).isSameAs(issuedAccessToken);
        assertThat(result.refreshToken()).isSameAs(issuedRefreshToken);
        verify(refreshTokenService).rotate(rawRefreshToken, REFRESHED_AT);
        verify(accessTokenService).issue(userId, REFRESHED_AT);
    }

    @Test
    @DisplayName("Refresh Token을 폐기한다")
    void logsOut() {
        String rawRefreshToken = "raw-refresh-token";

        authService.logout(rawRefreshToken, REFRESHED_AT);

        verify(refreshTokenService).revoke(rawRefreshToken, REFRESHED_AT);
        verifyNoInteractions(accessTokenService);
    }

    @Test
    @DisplayName("Refresh Token이 없으면 갱신을 시작하지 않는다")
    void rejectsMissingRefreshToken() {
        assertThatThrownBy(() -> authService.refresh(null, REFRESHED_AT))
                .isInstanceOf(NullPointerException.class);

        verifyNoInteractions(refreshTokenService, accessTokenService);
    }

    @Test
    @DisplayName("기준 시간이 없으면 갱신을 시작하지 않는다")
    void rejectsMissingTime() {
        assertThatThrownBy(() -> authService.refresh("raw-refresh-token", null))
                .isInstanceOf(NullPointerException.class);

        verifyNoInteractions(refreshTokenService, accessTokenService);
    }

    @Test
    @DisplayName("로그아웃 기준 시간이 없으면 토큰을 폐기하지 않는다")
    void rejectsLogoutWithoutTime() {
        assertThatThrownBy(() -> authService.logout("raw-refresh-token", null))
                .isInstanceOf(NullPointerException.class);

        verifyNoInteractions(refreshTokenService, accessTokenService);
    }
}
