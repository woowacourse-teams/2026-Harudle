package com.harudle.auth.infrastructure.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
public class OAuthLoginFailureHandler implements AuthenticationFailureHandler {

    private static final String ACCESS_DENIED_ERROR_CODE = "access_denied";
    private static final String APPLE_CANCELLED_ERROR_CODE = "user_cancelled_authorize";
    private static final String OAUTH_CALLBACK_PATH_PREFIX = "/login/oauth2/code/";
    private static final String UNKNOWN_PROVIDER = "unknown";

    private final OAuthFailureRedirector failureRedirector;
    private final OAuthEventLogger oAuthEventLogger;

    public OAuthLoginFailureHandler(
            OAuthFailureRedirector failureRedirector,
            OAuthEventLogger oAuthEventLogger
    ) {
        this.failureRedirector = Objects.requireNonNull(failureRedirector, "failureRedirector는 필수입니다.");
        this.oAuthEventLogger = Objects.requireNonNull(oAuthEventLogger, "oAuthEventLogger는 필수입니다.");
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        Objects.requireNonNull(exception, "OAuth 인증 예외는 필수입니다.");
        String provider = resolveProvider(request);
        OAuthFailureReason reason = resolveReason(exception, provider);
        if (reason == OAuthFailureReason.PROVIDER_ACCESS_DENIED) {
            oAuthEventLogger.infoRejected(provider, reason);
        } else {
            oAuthEventLogger.warnFailure(provider, reason, exception);
        }
        failureRedirector.redirect(response, reason);
    }

    private static OAuthFailureReason resolveReason(AuthenticationException exception, String provider) {
        if (exception instanceof OAuth2AuthenticationException oauthException) {
            String errorCode = oauthException.getError().getErrorCode();
            if (ACCESS_DENIED_ERROR_CODE.equals(errorCode)
                    || ("apple".equals(provider) && APPLE_CANCELLED_ERROR_CODE.equals(errorCode))) {
                return OAuthFailureReason.PROVIDER_ACCESS_DENIED;
            }
        }
        return OAuthFailureReason.PROVIDER_AUTHENTICATION_FAILED;
    }

    private static String resolveProvider(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        if (!requestUri.startsWith(OAUTH_CALLBACK_PATH_PREFIX)) {
            return UNKNOWN_PROVIDER;
        }
        return requestUri.substring(OAUTH_CALLBACK_PATH_PREFIX.length());
    }
}
