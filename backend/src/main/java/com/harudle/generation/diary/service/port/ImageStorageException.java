package com.harudle.generation.diary.service.port;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

public final class ImageStorageException extends RuntimeException {

    public enum DiagnosticType {
        AUTHENTICATION_ERROR,
        AUTHORIZATION_ERROR,
        CONFIGURATION_ERROR,
        PROVIDER_ERROR,
        CLIENT_ERROR,
        REQUEST_VALIDATION_ERROR,
        REQUEST_PREPARATION_ERROR,
        RESPONSE_PROCESSING_ERROR,
        OTHER
    }

    @Serial
    private static final long serialVersionUID = 1L;
    private final @Nullable DiagnosticType diagnosticType;

    public ImageStorageException(String message) {
        this(message, null);
    }

    public ImageStorageException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public ImageStorageException(String message, Throwable cause, @Nullable DiagnosticType diagnosticType) {
        super(message, cause);
        validateMessage(message);
        this.diagnosticType = diagnosticType;
    }

    public @Nullable DiagnosticType diagnosticType() {
        return diagnosticType;
    }

    private static void validateMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("이미지 저장소 오류 메시지는 필수입니다.");
        }
    }
}
