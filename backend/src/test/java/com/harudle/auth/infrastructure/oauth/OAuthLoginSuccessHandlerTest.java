package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.auth.application.InactiveUserException;
import com.harudle.auth.application.IssuedRefreshToken;
import com.harudle.auth.application.OAuthLoginCommand;
import com.harudle.auth.application.OAuthLoginResult;
import com.harudle.auth.application.OAuthLoginService;
import com.harudle.auth.application.RefreshTokenService;
import com.harudle.auth.application.RequiredOAuthProfileException;
import com.harudle.auth.domain.User;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.token.RefreshTokenCookieWriter;
import com.harudle.common.security.AccessTokenProperties;
import com.harudle.common.security.AuthProperties;
import com.harudle.common.security.LegacyCsrfCookieCleaner;
import com.harudle.common.security.RefreshTokenProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import tools.jackson.databind.ObjectMapper;

class OAuthLoginSuccessHandlerTest {

    private static final Instant LOGIN_AT = Instant.parse("2026-08-12T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(LOGIN_AT, ZoneOffset.UTC);

    private OAuthLoginService oAuthLoginService;
    private UserRepository userRepository;
    private RefreshTokenService refreshTokenService;
    private OAuthFailureRedirector failureRedirector;
    private OAuthLoginSuccessHandler handler;

    @BeforeEach
    void setUp() {
        oAuthLoginService = mock(OAuthLoginService.class);
        userRepository = mock(UserRepository.class);
        refreshTokenService = mock(RefreshTokenService.class);
        AuthProperties authProperties = createAuthProperties();
        failureRedirector = spy(new OAuthFailureRedirector(authProperties));

        handler = new OAuthLoginSuccessHandler(
                oAuthLoginService,
                new KakaoLoginCommandMapper(new ObjectMapper()),
                userRepository,
                refreshTokenService,
                new RefreshTokenCookieWriter(authProperties),
                createCsrfTokenRepository(),
                new LegacyCsrfCookieCleaner(),
                failureRedirector,
                authProperties,
                FIXED_CLOCK
        );
    }

    @Test
    @DisplayName("OAuth 로그인 성공 시 사용자를 조회하고 Refresh Token Cookie를 저장한 뒤 성공 URL로 이동한다")
    void completesOAuthLogin() throws Exception {
        User user = new User("user@example.com", "하루들", LOGIN_AT);
        OAuthLoginResult loginResult = new OAuthLoginResult(user.getId());
        IssuedRefreshToken issuedRefreshToken = new IssuedRefreshToken(
                "raw-refresh-token",
                LOGIN_AT.plus(Duration.ofDays(14))
        );
        when(oAuthLoginService.login(any(OAuthLoginCommand.class), eq(LOGIN_AT)))
                .thenReturn(loginResult);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(refreshTokenService.issue(user, LOGIN_AT)).thenReturn(issuedRefreshToken);

        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(
                new MockHttpServletRequest(),
                response,
                createKakaoAuthentication()
        );

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/auth/callback");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains("refresh_token=raw-refresh-token")
                .contains("HttpOnly")
                .contains("Path=/api/v1/auth");
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .filteredOn(cookie -> cookie.startsWith("XSRF-TOKEN=;"))
                .singleElement()
                .satisfies(cookie -> assertThat(cookie)
                        .contains("Path=/api/v1/auth")
                        .contains("Max-Age=0"));
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .filteredOn(cookie -> cookie.startsWith("XSRF-TOKEN=") && !cookie.contains("Max-Age=0"))
                .singleElement()
                .satisfies(cookie -> assertThat(cookie).contains("Path=/api/v1"));
        ArgumentCaptor<OAuthLoginCommand> commandCaptor = ArgumentCaptor.forClass(OAuthLoginCommand.class);
        verify(oAuthLoginService).login(commandCaptor.capture(), eq(LOGIN_AT));
        assertThat(commandCaptor.getValue().provider()).isEqualTo(OAuthProvider.KAKAO);
        assertThat(commandCaptor.getValue().providerSubject()).isEqualTo("12345");
        assertThat(commandCaptor.getValue().providerEmail()).isEqualTo("user@example.com");
        assertThat(commandCaptor.getValue().displayName()).isEqualTo("하루들");
        verify(userRepository).findById(user.getId());
        verify(refreshTokenService).issue(user, LOGIN_AT);
    }

    @Test
    @DisplayName("필수 OAuth 프로필이 없으면 Refresh Token을 발급하지 않고 실패 URL로 이동한다")
    void redirectsWhenRequiredProfileIsMissing() throws Exception {
        when(oAuthLoginService.login(any(OAuthLoginCommand.class), eq(LOGIN_AT)))
                .thenThrow(new RequiredOAuthProfileException("닉네임이 필요합니다."));

        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(
                new MockHttpServletRequest(),
                response,
                createKakaoAuthentication()
        );

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/auth/callback?error=oauth_failed");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        verify(failureRedirector).redirect(response, OAuthFailureReason.REQUIRED_PROFILE_MISSING);
        verifyNoInteractions(userRepository, refreshTokenService);
    }

    @Test
    @DisplayName("탈퇴한 사용자의 OAuth 로그인은 실패 URL로 이동한다")
    void redirectsWhenUserIsInactive() throws Exception {
        when(oAuthLoginService.login(any(OAuthLoginCommand.class), eq(LOGIN_AT)))
                .thenThrow(new InactiveUserException());

        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(
                new MockHttpServletRequest(),
                response,
                createKakaoAuthentication()
        );

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/auth/callback?error=oauth_failed");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        verify(failureRedirector).redirect(response, OAuthFailureReason.INACTIVE_USER);
        verifyNoInteractions(userRepository, refreshTokenService);
    }

    @Test
    @DisplayName("지원하지 않는 OAuth provider는 서비스 로그인으로 전달하지 않는다")
    void rejectsUnsupportedProvider() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(
                new MockHttpServletRequest(),
                response,
                createAuthentication("google")
        );

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/auth/callback?error=oauth_failed");
        verify(failureRedirector).redirect(response, OAuthFailureReason.UNSUPPORTED_PROVIDER);
        verifyNoInteractions(oAuthLoginService, userRepository, refreshTokenService);
    }

    @Test
    @DisplayName("카카오 프로필이 올바르지 않으면 서비스 로그인으로 전달하지 않고 실패 URL로 이동한다")
    void redirectsWhenProviderProfileIsInvalid() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(
                new MockHttpServletRequest(),
                response,
                createAuthenticationWithoutId()
        );

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/auth/callback?error=oauth_failed");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        verify(failureRedirector).redirect(response, OAuthFailureReason.INVALID_PROVIDER_PROFILE);
        verifyNoInteractions(oAuthLoginService, userRepository, refreshTokenService);
    }

    @Test
    @DisplayName("Refresh Token 발급 중 내부 인자 오류가 발생하면 내부 오류로 분류한다")
    void classifiesRefreshTokenIssueFailureAsInternalError() throws Exception {
        User user = new User("user@example.com", "하루들", LOGIN_AT);
        OAuthLoginResult loginResult = new OAuthLoginResult(user.getId());
        when(oAuthLoginService.login(any(OAuthLoginCommand.class), eq(LOGIN_AT)))
                .thenReturn(loginResult);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(refreshTokenService.issue(user, LOGIN_AT))
                .thenThrow(new IllegalArgumentException("내부 토큰 불변식 오류"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(
                new MockHttpServletRequest(),
                response,
                createKakaoAuthentication()
        );

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/auth/callback?error=oauth_failed");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        verify(failureRedirector).redirect(response, OAuthFailureReason.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("로그인 결과의 사용자가 없으면 내부 정합성 오류로 분류한다")
    void classifiesMissingLoginUserAsConsistencyError() throws Exception {
        UUID userId = UUID.randomUUID();
        when(oAuthLoginService.login(any(OAuthLoginCommand.class), eq(LOGIN_AT)))
                .thenReturn(new OAuthLoginResult(userId));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(
                new MockHttpServletRequest(),
                response,
                createKakaoAuthentication()
        );

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/auth/callback?error=oauth_failed");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        verify(failureRedirector).redirect(response, OAuthFailureReason.INTERNAL_CONSISTENCY_ERROR);
        verifyNoInteractions(refreshTokenService);
    }

    private Authentication createKakaoAuthentication() {
        return createAuthentication("kakao");
    }

    private Authentication createAuthentication(String registrationId) {
        Map<String, Object> attributes = Map.of(
                "id", 12345L,
                "kakao_account", Map.of(
                        "email", "user@example.com",
                        "profile", Map.of(
                                "nickname", "하루들"
                        )
                )
        );
        OAuth2User principal = new DefaultOAuth2User(
                List.of(),
                attributes,
                "id"
        );

        return new OAuth2AuthenticationToken(
                principal,
                principal.getAuthorities(),
                registrationId
        );
    }

    private Authentication createAuthenticationWithoutId() {
        Map<String, Object> attributes = Map.of(
                "kakao_account", Map.of(
                        "profile", Map.of("nickname", "하루들")
                )
        );
        OAuth2User principal = new DefaultOAuth2User(
                List.of(),
                attributes,
                "kakao_account"
        );
        return new OAuth2AuthenticationToken(
                principal,
                principal.getAuthorities(),
                "kakao"
        );
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

    private CookieCsrfTokenRepository createCsrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName("XSRF-TOKEN");
        repository.setCookiePath("/api/v1");
        repository.setCookieCustomizer(builder -> builder.sameSite("Lax"));

        return repository;
    }
}
