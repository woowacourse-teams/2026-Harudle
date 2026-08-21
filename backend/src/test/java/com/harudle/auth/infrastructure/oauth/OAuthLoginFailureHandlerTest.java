package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.harudle.common.security.AccessTokenProperties;
import com.harudle.common.security.AuthProperties;
import com.harudle.common.security.RefreshTokenProperties;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

class OAuthLoginFailureHandlerTest {

    @Test
    @DisplayName("OAuth 인증이 실패하면 토큰 정보 없이 실패 URL로 리다이렉트한다")
    void redirectsToFailurePage() throws Exception {
        OAuthFailureRedirector redirector = spy(new OAuthFailureRedirector(createAuthProperties()));
        OAuthEventLogger oAuthEventLogger = mock(OAuthEventLogger.class);
        OAuthLoginFailureHandler handler = new OAuthLoginFailureHandler(redirector, oAuthEventLogger);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/login/oauth2/code/kakao"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        BadCredentialsException exception = new BadCredentialsException("provider error");

        handler.onAuthenticationFailure(
                request,
                response,
                exception
        );

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/auth/callback?error=oauth_failed");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        verify(redirector).redirect(response, OAuthFailureReason.PROVIDER_AUTHENTICATION_FAILED);
        verify(oAuthEventLogger).warnFailure(
                "kakao",
                OAuthFailureReason.PROVIDER_AUTHENTICATION_FAILED,
                exception
        );
    }

    @Test
    @DisplayName("사용자가 OAuth 접근을 거부하면 공급자 접근 거부로 분류한다")
    void classifiesProviderAccessDenied() throws Exception {
        OAuthFailureRedirector redirector = mock(OAuthFailureRedirector.class);
        OAuthEventLogger oAuthEventLogger = mock(OAuthEventLogger.class);
        OAuthLoginFailureHandler handler = new OAuthLoginFailureHandler(redirector, oAuthEventLogger);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/login/oauth2/code/kakao"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(
                request,
                response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied"))
        );

        verify(redirector).redirect(response, OAuthFailureReason.PROVIDER_ACCESS_DENIED);
        verify(oAuthEventLogger).infoRejected("kakao", OAuthFailureReason.PROVIDER_ACCESS_DENIED);
    }

    private AuthProperties createAuthProperties() {
        return new AuthProperties(
                List.of("http://localhost:5173"),
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
