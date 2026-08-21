package com.harudle.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;

@Component
@NullMarked
final class ApiExceptionLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionLogger.class);
    private static final String API_EXCEPTION_LOG_FORMAT =
            "event=api_exception errorCode={} httpStatus={} method={} path={} exceptionType={}";

    void error(
            ErrorType errorType,
            Throwable exception,
            HttpServletRequest request
    ) {
        log(errorType.code(), errorType.status(), exception, request);
    }

    void error(
            HttpStatusCode statusCode,
            Throwable exception,
            HttpServletRequest request
    ) {
        log(FrameworkErrorType.codeFor(statusCode), statusCode, exception, request);
    }

    private void log(
            String errorCode,
            HttpStatusCode statusCode,
            Throwable exception,
            HttpServletRequest request
    ) {
        LOGGER.error(
                API_EXCEPTION_LOG_FORMAT,
                errorCode,
                statusCode.value(),
                request.getMethod(),
                resolvePath(request),
                exception.getClass().getSimpleName(),
                exception
        );
    }

    private static String resolvePath(HttpServletRequest request) {
        Object pathPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pathPattern != null) {
            return pathPattern.toString();
        }
        return request.getRequestURI();
    }
}
