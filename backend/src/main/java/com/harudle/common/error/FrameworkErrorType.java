package com.harudle.common.error;

import java.util.Arrays;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

enum FrameworkErrorType {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    API_NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    FrameworkErrorType(HttpStatus status) {
        this.status = status;
    }

    static String codeFor(HttpStatusCode statusCode) {
        return Arrays.stream(values())
                .filter(errorType -> errorType.hasStatus(statusCode))
                .findFirst()
                .map(FrameworkErrorType::name)
                .orElseGet(() -> "HTTP_" + statusCode.value());
    }

    private boolean hasStatus(HttpStatusCode statusCode) {
        return status.value() == statusCode.value();
    }
}
