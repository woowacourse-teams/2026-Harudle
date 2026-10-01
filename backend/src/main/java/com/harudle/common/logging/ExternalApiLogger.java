package com.harudle.common.logging;

import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
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
    private static final String RESPONSE_DIAGNOSTICS_FORMAT = LOG_FORMAT
            + " finishReason={} candidateTokenCount={} thoughtTokenCount={}"
            + " maxOutputTokens={} responseLength={}";

    public void warn(ExternalApiFailure failure, Throwable exception) {
        warn(EXTERNAL_API_FAILURE_EVENT, failure, exception);
    }

    public void warnCompensation(ExternalApiFailure failure, Throwable exception) {
        warn(COMPENSATION_FAILURE_EVENT, failure, exception);
    }

    private void warn(String event, ExternalApiFailure failure, Throwable exception) {
        withSafeFields(LOGGER.atWarn(), event, failure, exception)
                .setCause(sanitizedStackTrace(exception))
                .log(LOG_FORMAT,
                event,
                safe(failure.provider()),
                safe(failure.operation()),
                safe(failure.failureType()),
                safe(failure.providerStatus()),
                safe(failure.providerCode()),
                safe(failure.providerRequestId()),
                exception.getClass().getSimpleName()
        );
    }

    public void error(ExternalApiFailure failure, Throwable exception) {
        withSafeFields(LOGGER.atError(), EXTERNAL_API_FAILURE_EVENT, failure, exception)
                .setCause(sanitizedStackTrace(exception))
                .log(LOG_FORMAT,
                EXTERNAL_API_FAILURE_EVENT,
                safe(failure.provider()),
                safe(failure.operation()),
                safe(failure.failureType()),
                safe(failure.providerStatus()),
                safe(failure.providerCode()),
                safe(failure.providerRequestId()),
                exception.getClass().getSimpleName()
        );
    }

    public void error(
            ExternalApiFailure failure,
            Throwable exception,
            ExternalApiResponseDiagnostics diagnostics
    ) {
        withSafeFields(LOGGER.atError(), EXTERNAL_API_FAILURE_EVENT, failure, exception)
                .addKeyValue("finishReason", safe(diagnostics.finishReason()))
                .addKeyValue("candidateTokenCount", number(diagnostics.candidateTokenCount()))
                .addKeyValue("thoughtTokenCount", number(diagnostics.thoughtTokenCount()))
                .addKeyValue("maxOutputTokens", number(diagnostics.maxOutputTokens()))
                .addKeyValue("responseLength", number(diagnostics.responseLength()))
                .setCause(sanitizedStackTrace(exception))
                .log(RESPONSE_DIAGNOSTICS_FORMAT,
                EXTERNAL_API_FAILURE_EVENT,
                safe(failure.provider()),
                safe(failure.operation()),
                safe(failure.failureType()),
                safe(failure.providerStatus()),
                safe(failure.providerCode()),
                safe(failure.providerRequestId()),
                exception.getClass().getSimpleName(),
                safe(diagnostics.finishReason()),
                number(diagnostics.candidateTokenCount()),
                number(diagnostics.thoughtTokenCount()),
                number(diagnostics.maxOutputTokens()),
                number(diagnostics.responseLength())
        );
    }

    private static LoggingEventBuilder withSafeFields(
            LoggingEventBuilder builder,
            String event,
            ExternalApiFailure failure,
            Throwable exception
    ) {
        return builder.addKeyValue("event", event)
                .addKeyValue("provider", safe(failure.provider()))
                .addKeyValue("operation", safe(failure.operation()))
                .addKeyValue("failureType", safe(failure.failureType()))
                .addKeyValue("providerStatus", safe(failure.providerStatus()))
                .addKeyValue("providerCode", safe(failure.providerCode()))
                .addKeyValue("providerRequestId", safe(failure.providerRequestId()))
                .addKeyValue("exceptionType", exception.getClass().getSimpleName());
    }

    private static String number(@Nullable Integer value) {
        return value == null ? EMPTY_FIELD_VALUE : value.toString();
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
