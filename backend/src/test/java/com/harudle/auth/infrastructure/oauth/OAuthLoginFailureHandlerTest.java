package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.common.security.AccessTokenProperties;
import com.harudle.common.security.AuthProperties;
import com.harudle.common.security.RefreshTokenProperties;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

class OAuthLoginFailureHandlerTest {

    @Test
    @DisplayName("OAuth 인증이 실패하면 토큰 정보 없이 실패 URL로 리다이렉트한다")
    void redirectsToFailurePage() throws Exception {
        OAuthLoginFailureHandler handler = new OAuthLoginFailureHandler(createAuthProperties());
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(
                new MockHttpServletRequest(),
                response,
                new BadCredentialsException("provider error")
        );

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/auth/callback?error=oauth_failed");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
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
