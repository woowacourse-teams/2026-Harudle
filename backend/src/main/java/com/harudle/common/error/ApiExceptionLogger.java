package com.harudle.common.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.IdentityHashMap;
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
    private static final String UNMATCHED_PATH = "UNMATCHED";
    private static final int MAX_EXCEPTION_DEPTH = 32;
    private static final int MAX_SUPPRESSED_PER_EXCEPTION = 16;
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
        String path = resolvePath(request);
        LOGGER.atError()
                .addKeyValue("event", "api_exception")
                .addKeyValue("errorCode", errorCode)
                .addKeyValue("httpStatus", statusCode.value())
                .addKeyValue("method", request.getMethod())
                .addKeyValue("path", path)
                .addKeyValue("exceptionType", exception.getClass().getSimpleName())
                .setCause(sanitizedStackTrace(exception))
                .log(API_EXCEPTION_LOG_FORMAT, errorCode, statusCode.value(),
                        request.getMethod(), path, exception.getClass().getSimpleName());
    }

    private static String resolvePath(HttpServletRequest request) {
        Object pathPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pathPattern != null) {
            return pathPattern.toString();
        }
        return UNMATCHED_PATH;
    }

    private static Throwable sanitizedStackTrace(Throwable exception) {
        return sanitizedStackTrace(exception, new IdentityHashMap<>(), 0);
    }

    private static Throwable sanitizedStackTrace(
            Throwable exception,
            IdentityHashMap<Throwable, Boolean> seen,
            int depth
    ) {
        if (depth >= MAX_EXCEPTION_DEPTH || seen.put(exception, Boolean.TRUE) != null) {
            return new SanitizedException("java.lang.Throwable (exception chain truncated)", null);
        }
        Throwable cause = exception.getCause() == null
                ? null : sanitizedStackTrace(exception.getCause(), seen, depth + 1);
        SanitizedException sanitized = new SanitizedException(exception.getClass().getName(), cause);
        sanitized.setStackTrace(exception.getStackTrace());
        Throwable[] suppressed = exception.getSuppressed();
        for (int index = 0; index < Math.min(suppressed.length, MAX_SUPPRESSED_PER_EXCEPTION); index++) {
            sanitized.addSuppressed(sanitizedStackTrace(suppressed[index], seen, depth + 1));
        }
        return sanitized;
    }

    private static final class SanitizedException extends RuntimeException {

        private final String originalType;

        private SanitizedException(String originalType, Throwable cause) {
            super("message omitted", cause);
            this.originalType = originalType;
        }

        @Override
        public String toString() {
            return originalType + ": message omitted";
        }
    }
}
