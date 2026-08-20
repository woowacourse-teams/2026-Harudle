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
    private static final String UNKNOWN_PROVIDER = "unknown";
    private static final String NO_STORE = "no-store";

    private final OAuthLoginService oAuthLoginService;
    private final KakaoLoginCommandMapper kakaoLoginCommandMapper;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenCookieWriter refreshTokenCookieWriter;
    private final CookieCsrfTokenRepository csrfTokenRepository;
    private final OAuthFailureRedirector failureRedirector;
    private final OAuthEventLogger oAuthEventLogger;
    private final LegacyCsrfCookieCleaner legacyCsrfCookieCleaner;
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
            OAuthFailureRedirector failureRedirector,
            OAuthEventLogger oAuthEventLogger,
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
        this.failureRedirector = Objects.requireNonNull(failureRedirector, "failureRedirector는 필수입니다.");
        this.oAuthEventLogger = Objects.requireNonNull(oAuthEventLogger, "oAuthEventLogger는 필수입니다.");
        this.successRedirect = extractSuccessRedirect(authProperties);
        this.clock = Objects.requireNonNull(authClock, "authClock는 필수입니다.");
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {
        String provider = resolveProvider(authentication);
        IssuedRefreshToken issuedRefreshToken;
        try {
            issuedRefreshToken = completeLogin(authentication);
        } catch (InvalidOAuthProfileException exception) {
            redirectProviderFailure(
                    response,
                    provider,
                    OAuthFailureReason.INVALID_PROVIDER_PROFILE,
                    exception
            );
            return;
        } catch (RequiredOAuthProfileException exception) {
            redirectExpectedFailure(response, provider, OAuthFailureReason.REQUIRED_PROFILE_MISSING);
            return;
        } catch (InactiveUserException exception) {
            redirectExpectedFailure(response, provider, OAuthFailureReason.INACTIVE_USER);
            return;
        } catch (UnsupportedOAuthProviderException exception) {
            redirectInternalFailure(response, provider, OAuthFailureReason.UNSUPPORTED_PROVIDER, exception);
            return;
        } catch (OAuthLoginConsistencyException exception) {
            redirectInternalFailure(response, provider, OAuthFailureReason.INTERNAL_CONSISTENCY_ERROR, exception);
            return;
        } catch (RuntimeException exception) {
            redirectInternalFailure(response, provider, OAuthFailureReason.INTERNAL_ERROR, exception);
            return;
        }

        writeSuccessfulResponse(request, response, issuedRefreshToken, provider);
    }

    private IssuedRefreshToken completeLogin(Authentication authentication) {
        OAuth2AuthenticationToken oauthAuthentication = extractOAuthAuthentication(authentication);
        validateRegistrationId(oauthAuthentication);

        Instant now = Instant.now(clock);
        OAuthLoginCommand command = kakaoLoginCommandMapper.map(
                oauthAuthentication.getPrincipal().getAttributes()
        );
        OAuthLoginResult loginResult = oAuthLoginService.login(command, now);
        User user = findUser(loginResult);
        return refreshTokenService.issue(user, now);
    }

    private OAuth2AuthenticationToken extractOAuthAuthentication(Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken oauthAuthentication) {
            return oauthAuthentication;
        }
        throw new OAuthLoginConsistencyException("OAuth2 인증 정보가 필요합니다.");
    }

    private void validateRegistrationId(OAuth2AuthenticationToken authentication) {
        if (KAKAO_REGISTRATION_ID.equals(authentication.getAuthorizedClientRegistrationId())) {
            return;
        }

        throw new UnsupportedOAuthProviderException();
    }

    private User findUser(OAuthLoginResult loginResult) {
        return userRepository.findById(loginResult.userId())
                .orElseThrow(() -> new OAuthLoginConsistencyException("로그인한 사용자를 찾을 수 없습니다."));
    }

    private void redirectExpectedFailure(
            HttpServletResponse response,
            String provider,
            OAuthFailureReason reason
    ) throws IOException {
        oAuthEventLogger.infoRejected(provider, reason);
        failureRedirector.redirect(response, reason);
    }

    private void redirectProviderFailure(
            HttpServletResponse response,
            String provider,
            OAuthFailureReason reason,
            RuntimeException exception
    ) throws IOException {
        oAuthEventLogger.warnFailure(provider, reason, exception);
        failureRedirector.redirect(response, reason);
    }

    private void redirectInternalFailure(
            HttpServletResponse response,
            String provider,
            OAuthFailureReason reason,
            RuntimeException exception
    ) throws IOException {
        oAuthEventLogger.errorFailure(provider, reason, exception);
        failureRedirector.redirect(response, reason);
    }

    private void writeSuccessfulResponse(
            HttpServletRequest request,
            HttpServletResponse response,
            IssuedRefreshToken issuedRefreshToken,
            String provider
    ) throws IOException {
        try {
            refreshTokenCookieWriter.write(response, issuedRefreshToken);
            writeCsrfToken(request, response);
            redirectToSuccess(response);
        } catch (RuntimeException | IOException exception) {
            oAuthEventLogger.errorFailure(provider, OAuthFailureReason.INTERNAL_ERROR, exception);
            throw exception;
        }
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

    private static String resolveProvider(Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken oauthAuthentication) {
            return oauthAuthentication.getAuthorizedClientRegistrationId();
        }
        return UNKNOWN_PROVIDER;
    }

    private URI extractSuccessRedirect(AuthProperties authProperties) {
        Objects.requireNonNull(authProperties, "authProperties는 필수입니다.");

        return Objects.requireNonNull(
                authProperties.successRedirect(),
                "successRedirect 설정은 필수입니다."
        );
    }

}
