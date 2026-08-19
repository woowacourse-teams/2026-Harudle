package com.harudle.auth.infrastructure.oauth;

import com.harudle.auth.application.InactiveUserException;
import com.harudle.auth.application.IssuedRefreshToken;
import com.harudle.auth.application.OAuthLoginCommand;
import com.harudle.auth.application.OAuthLoginResult;
import com.harudle.auth.application.OAuthLoginService;
import com.harudle.auth.application.RefreshTokenService;
import com.harudle.auth.application.RequiredOAuthProfileException;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.token.RefreshTokenCookieWriter;
import com.harudle.common.security.AuthProperties;
import com.harudle.common.security.LegacyCsrfCookieCleaner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
public class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final String KAKAO_REGISTRATION_ID = "kakao";
    private static final String NO_STORE = "no-store";

    private final OAuthLoginService oAuthLoginService;
    private final KakaoLoginCommandMapper kakaoLoginCommandMapper;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenCookieWriter refreshTokenCookieWriter;
    private final CookieCsrfTokenRepository csrfTokenRepository;
    private final LegacyCsrfCookieCleaner legacyCsrfCookieCleaner;
    private final OAuthLoginFailureHandler oAuthLoginFailureHandler;
    private final URI successRedirect;
    private final Clock clock;

    public OAuthLoginSuccessHandler(
            OAuthLoginService oAuthLoginService,
            KakaoLoginCommandMapper kakaoLoginCommandMapper,
            UserRepository userRepository,
            RefreshTokenService refreshTokenService,
            RefreshTokenCookieWriter refreshTokenCookieWriter,
            CookieCsrfTokenRepository csrfTokenRepository,
            LegacyCsrfCookieCleaner legacyCsrfCookieCleaner,
            OAuthLoginFailureHandler oAuthLoginFailureHandler,
            AuthProperties authProperties,
            @Qualifier("authClock")
            Clock authClock
    ) {
        this.oAuthLoginService = oAuthLoginService;
        this.kakaoLoginCommandMapper = kakaoLoginCommandMapper;
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
        this.refreshTokenCookieWriter = refreshTokenCookieWriter;
        this.csrfTokenRepository = csrfTokenRepository;
        this.legacyCsrfCookieCleaner = legacyCsrfCookieCleaner;
        this.oAuthLoginFailureHandler = oAuthLoginFailureHandler;
        this.successRedirect = extractSuccessRedirect(authProperties);
        this.clock = Objects.requireNonNull(authClock, "authClock는 필수입니다.");
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {
        OAuth2AuthenticationToken oauthAuthentication = extractOAuthAuthentication(authentication);

        try {
            validateRegistrationId(oauthAuthentication);

            Instant now = Instant.now(clock);
            OAuthLoginCommand command = kakaoLoginCommandMapper.map(
                    oauthAuthentication.getPrincipal().getAttributes()
            );

            OAuthLoginResult loginResult = oAuthLoginService.login(command, now);
            User user = findUser(loginResult);
            IssuedRefreshToken issuedRefreshToken = refreshTokenService.issue(user, now);

            refreshTokenCookieWriter.write(response, issuedRefreshToken);
            writeCsrfToken(request, response);
            redirectToSuccess(response);
        } catch (IllegalArgumentException | RequiredOAuthProfileException | InactiveUserException exception) {
            oAuthLoginFailureHandler.redirect(response);
        }
    }

    private OAuth2AuthenticationToken extractOAuthAuthentication(Authentication authentication) {
        Objects.requireNonNull(authentication, "authentication은 필수입니다.");

        return (OAuth2AuthenticationToken) authentication;
    }

    private void validateRegistrationId(OAuth2AuthenticationToken authentication) {
        if (KAKAO_REGISTRATION_ID.equals(authentication.getAuthorizedClientRegistrationId())) {
            return;
        }

        throw new IllegalArgumentException("지원하지 않는 OAuth 제공자입니다.");
    }

    private User findUser(OAuthLoginResult loginResult) {
        return userRepository.findById(loginResult.userId())
                .orElseThrow(() -> new IllegalStateException("로그인한 사용자를 찾을 수 없습니다."));
    }

    private void redirectToSuccess(HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
        response.sendRedirect(successRedirect.toString());
    }

    private void writeCsrfToken(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        CsrfToken csrfToken = csrfTokenRepository.generateToken(request);
        legacyCsrfCookieCleaner.clear(response);
        csrfTokenRepository.saveToken(csrfToken, request, response);
    }

    private URI extractSuccessRedirect(AuthProperties authProperties) {
        Objects.requireNonNull(authProperties, "authProperties는 필수입니다.");

        return Objects.requireNonNull(
                authProperties.successRedirect(),
                "successRedirect 설정은 필수입니다."
        );
    }

}
