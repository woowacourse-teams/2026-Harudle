package com.harudle.auth.infrastructure.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
public class OAuthLoginFailureHandler implements AuthenticationFailureHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(OAuthLoginFailureHandler.class);
    private static final String ACCESS_DENIED_ERROR_CODE = "access_denied";

    private final OAuthFailureRedirector failureRedirector;

    public OAuthLoginFailureHandler(OAuthFailureRedirector failureRedirector) {
        this.failureRedirector = Objects.requireNonNull(failureRedirector, "failureRedirector는 필수입니다.");
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        Objects.requireNonNull(exception, "OAuth 인증 예외는 필수입니다.");
        OAuthFailureReason reason = resolveReason(exception);
        if (reason == OAuthFailureReason.PROVIDER_ACCESS_DENIED) {
            LOGGER.info("OAuth 공급자 인증이 사용자에 의해 거부되었습니다. reason={}", reason);
        } else {
            LOGGER.warn(
                    "OAuth 공급자 인증에 실패했습니다. reason={}, exceptionType={}",
                    reason,
                    exception.getClass().getSimpleName()
            );
        }
        failureRedirector.redirect(response, reason);
    }

    private static OAuthFailureReason resolveReason(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauthException
                && ACCESS_DENIED_ERROR_CODE.equals(oauthException.getError().getErrorCode())) {
            return OAuthFailureReason.PROVIDER_ACCESS_DENIED;
        }
        return OAuthFailureReason.PROVIDER_AUTHENTICATION_FAILED;
    }
}
