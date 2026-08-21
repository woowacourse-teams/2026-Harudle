package com.harudle.auth.infrastructure.oauth;

import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@NullMarked
final class OAuthEventLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(OAuthEventLogger.class);
    private static final Pattern SAFE_PROVIDER_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final String UNKNOWN_PROVIDER = "unknown";

    void infoRejected(String provider, OAuthFailureReason reason) {
        LOGGER.info(
                "event=oauth_login_rejected provider={} reason={}",
                normalizeProvider(provider),
                reason
        );
    }

    void warnFailure(
            String provider,
            OAuthFailureReason reason,
            Throwable exception
    ) {
        LOGGER.warn(
                "event=oauth_login_failure provider={} reason={} exceptionType={}",
                normalizeProvider(provider),
                reason,
                exception.getClass().getSimpleName()
        );
    }

    void errorFailure(
            String provider,
            OAuthFailureReason reason,
            Throwable exception
    ) {
        LOGGER.error(
                "event=oauth_login_failure provider={} reason={} exceptionType={}",
                normalizeProvider(provider),
                reason,
                exception.getClass().getSimpleName(),
                sanitizedStackTrace(exception)
        );
    }

    private static Throwable sanitizedStackTrace(Throwable exception) {
        RuntimeException sanitized = new RuntimeException("OAuth 인증 처리 실패");
        sanitized.setStackTrace(exception.getStackTrace());
        return sanitized;
    }

    private static String normalizeProvider(String provider) {
        if (SAFE_PROVIDER_PATTERN.matcher(provider).matches()) {
            return provider;
        }
        return UNKNOWN_PROVIDER;
    }
}
