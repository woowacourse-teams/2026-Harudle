package com.harudle.auth.infrastructure.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.common.security.AccessTokenProperties;
import com.harudle.common.security.AuthProperties;
import com.harudle.common.security.RefreshTokenProperties;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RefreshTokenCookieReaderTest {

    private RefreshTokenCookieReader reader;

    @BeforeEach
    void setUp() {
        reader = new RefreshTokenCookieReader(createAuthProperties());
    }

    @Test
    @DisplayName("설정된 이름의 Refresh Token Cookie를 읽는다")
    void readsConfiguredCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie("other_cookie", "other-value"),
                new Cookie("refresh_token", "raw-refresh-token")
        );

        Optional<String> result = reader.read(request);

        assertThat(result).contains("raw-refresh-token");
    }

    @Test
    @DisplayName("Cookie가 없으면 빈 값을 반환한다")
    void returnsEmptyWhenCookiesAreMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(reader.read(request)).isEmpty();
    }

    @Test
    @DisplayName("설정된 이름의 Cookie가 없으면 빈 값을 반환한다")
    void returnsEmptyWhenConfiguredCookieIsMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("other_cookie", "other-value"));

        assertThat(reader.read(request)).isEmpty();
    }

    @Test
    @DisplayName("빈 Refresh Token Cookie는 읽지 않는다")
    void returnsEmptyWhenConfiguredCookieIsBlank() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("refresh_token", "   "));

        assertThat(reader.read(request)).isEmpty();
    }

    @Test
    @DisplayName("요청이 없으면 Cookie를 읽을 수 없다")
    void rejectsMissingRequest() {
        assertThatThrownBy(() -> reader.read(null))
                .isInstanceOf(NullPointerException.class);
    }

    private AuthProperties createAuthProperties() {
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
                new RefreshTokenProperties(
                        "refresh_token",
                        "/api/v1/auth",
                        false,
                        "Lax",
                        Duration.ofDays(14)
                )
        );
    }
}
