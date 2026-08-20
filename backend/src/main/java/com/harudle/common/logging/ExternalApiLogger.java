package com.harudle.common.logging;

import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@NullMarked
public final class ExternalApiLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExternalApiLogger.class);
    private static final Pattern SAFE_FIELD_VALUE_PATTERN = Pattern.compile("[A-Za-z0-9_.:/+\\-]{1,128}");
    private static final String EMPTY_FIELD_VALUE = "none";
    private static final String INVALID_FIELD_VALUE = "invalid";
    private static final String EXTERNAL_API_FAILURE_EVENT = "external_api_failure";
    private static final String COMPENSATION_FAILURE_EVENT = "compensation_failure";
    private static final String LOG_FORMAT =
            "event={} provider={} operation={} failureType={} providerStatus={} "
                    + "providerCode={} providerRequestId={} exceptionType={}";

    public void warn(ExternalApiFailure failure, Throwable exception) {
        warn(EXTERNAL_API_FAILURE_EVENT, failure, exception);
    }

    public void warnCompensation(ExternalApiFailure failure, Throwable exception) {
        warn(COMPENSATION_FAILURE_EVENT, failure, exception);
    }

    private void warn(String event, ExternalApiFailure failure, Throwable exception) {
        LOGGER.warn(
                LOG_FORMAT,
                event,
                safe(failure.provider()),
                safe(failure.operation()),
                safe(failure.failureType()),
                safe(failure.providerStatus()),
                safe(failure.providerCode()),
                safe(failure.providerRequestId()),
                exception.getClass().getSimpleName(),
                sanitizedStackTrace(exception)
        );
    }

    public void error(ExternalApiFailure failure, Throwable exception) {
        LOGGER.error(
                LOG_FORMAT,
                EXTERNAL_API_FAILURE_EVENT,
                safe(failure.provider()),
                safe(failure.operation()),
                safe(failure.failureType()),
                safe(failure.providerStatus()),
                safe(failure.providerCode()),
                safe(failure.providerRequestId()),
                exception.getClass().getSimpleName(),
                sanitizedStackTrace(exception)
        );
    }

    private static String safe(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return EMPTY_FIELD_VALUE;
        }
        if (SAFE_FIELD_VALUE_PATTERN.matcher(value).matches()) {
            return value;
        }
        return INVALID_FIELD_VALUE;
    }

    private static Throwable sanitizedStackTrace(Throwable exception) {
        RuntimeException sanitized = new RuntimeException("외부 연동 실패");
        sanitized.setStackTrace(exception.getStackTrace());
        return sanitized;
    }
}
