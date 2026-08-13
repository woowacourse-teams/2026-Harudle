package com.harudle.auth.infrastructure.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.auth.application.IssuedRefreshToken;
import com.harudle.common.security.AccessTokenProperties;
import com.harudle.common.security.AuthProperties;
import com.harudle.common.security.RefreshTokenProperties;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

class RefreshTokenCookieWriterTest {

    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(14);
    private static final Instant ISSUED_AT = Instant.parse("2026-08-12T10:00:00Z");

    private RefreshTokenCookieWriter refreshTokenCookieWriter;

    @BeforeEach
    void setUp() {
        refreshTokenCookieWriter = new RefreshTokenCookieWriter(createAuthProperties());
    }

    @Test
    @DisplayName("Refresh Token을 보안 속성을 가진 HttpOnly Cookie로 저장한다")
    void writesRefreshTokenCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        IssuedRefreshToken issuedRefreshToken = new IssuedRefreshToken(
                "raw-refresh-token",
                ISSUED_AT.plus(REFRESH_TOKEN_TTL)
        );

        refreshTokenCookieWriter.write(response, issuedRefreshToken);

        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .contains("refresh_token=raw-refresh-token")
                .contains("Path=/api/v1/auth")
                .contains("Max-Age=1209600")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Lax");
    }

    @Test
    @DisplayName("Refresh Token Cookie를 삭제할 때 같은 경로와 Max-Age 0을 사용한다")
    void clearsRefreshTokenCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        refreshTokenCookieWriter.clear(response);

        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .contains("refresh_token=")
                .contains("Path=/api/v1/auth")
                .contains("Max-Age=0")
                .contains("HttpOnly")
                .contains("SameSite=Lax");
    }

    @Test
    @DisplayName("Refresh Token Cookie를 저장할 응답이 없으면 실패한다")
    void rejectsNullResponse() {
        IssuedRefreshToken issuedRefreshToken = new IssuedRefreshToken(
                "raw-refresh-token",
                ISSUED_AT.plus(REFRESH_TOKEN_TTL)
        );

        assertThatThrownBy(() -> refreshTokenCookieWriter.write(null, issuedRefreshToken))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Refresh Token이 없으면 Cookie를 저장할 수 없다")
    void rejectsNullIssuedRefreshToken() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> refreshTokenCookieWriter.write(response, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Refresh Token Cookie 이름이 없으면 생성할 수 없다")
    void rejectsMissingCookieName() {
        AuthProperties authProperties = createAuthProperties(
                new RefreshTokenProperties(
                        null,
                        "/api/v1/auth",
                        true,
                        "Lax",
                        REFRESH_TOKEN_TTL
                )
        );

        assertThatThrownBy(() -> new RefreshTokenCookieWriter(authProperties))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Refresh Token TTL이 0이면 Cookie writer를 생성할 수 없다")
    void rejectsZeroTtl() {
        AuthProperties authProperties = createAuthProperties(
                new RefreshTokenProperties(
                        "refresh_token",
                        "/api/v1/auth",
                        true,
                        "Lax",
                        Duration.ZERO
                )
        );

        assertThatThrownBy(() -> new RefreshTokenCookieWriter(authProperties))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AuthProperties createAuthProperties() {
        return createAuthProperties(
                new RefreshTokenProperties(
                        "refresh_token",
                        "/api/v1/auth",
                        true,
                        "Lax",
                        REFRESH_TOKEN_TTL
                )
        );
    }

    private AuthProperties createAuthProperties(RefreshTokenProperties refreshTokenProperties) {
        return new AuthProperties(
                "http://localhost:5173",
                URI.create("http://localhost:5173/auth/callback"),
                URI.create("http://localhost:5173/auth/callback?error=oauth_failed"),
                new AccessTokenProperties(
                        "harudle",
                        "harudle-api",
                        "secret",
                        Duration.ofMinutes(30)
                ),
                refreshTokenProperties
        );
    }
}
