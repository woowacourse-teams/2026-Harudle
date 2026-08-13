package com.harudle.generation.service.exception;

import java.io.Serial;

public final class AiGenerationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final AiGenerationErrorType errorType;

    public AiGenerationException(AiGenerationErrorType errorType, String message) {
        this(errorType, message, null);
    }

    public AiGenerationException(
            AiGenerationErrorType errorType,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        validateErrorType(errorType);
        validateMessage(message);
        this.errorType = errorType;
    }

    public AiGenerationErrorType errorType() {
        return errorType;
    }

    private static void validateErrorType(AiGenerationErrorType errorType) {
        if (errorType == null) {
            throw new IllegalArgumentException("AI 생성 오류 타입은 필수입니다.");
        }
    }

    private static void validateMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("AI 생성 오류 메시지는 필수입니다.");
        }
    }
}
