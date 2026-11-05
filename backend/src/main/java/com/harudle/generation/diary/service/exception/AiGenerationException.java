package com.harudle.generation.diary.service.exception;

import com.harudle.generation.diary.domain.GenerationErrorCode;
import java.io.Serial;

public final class AiGenerationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final AiGenerationErrorType errorType;
    private final AiProviderFailureType providerFailureType;

    public AiGenerationException(AiGenerationErrorType errorType, String message) {
        this(errorType, message, null);
    }

    public AiGenerationException(AiGenerationErrorType errorType, String message, Throwable cause) {
        this(errorType, message, cause, AiProviderFailureType.UNKNOWN);
    }

    public AiGenerationException(
            AiGenerationErrorType errorType,
            String message,
            Throwable cause,
            AiProviderFailureType providerFailureType
    ) {
        super(message, cause);
        validateErrorType(errorType);
        validateMessage(message);
        validateProviderFailureType(providerFailureType);
        this.errorType = errorType;
        this.providerFailureType = providerFailureType;
    }

    public AiGenerationErrorType errorType() {
        return errorType;
    }

    public AiProviderFailureType providerFailureType() {
        return providerFailureType;
    }

    public GenerationErrorCode errorCode() {
        return switch (errorType) {
            case PROVIDER_ERROR -> switch (providerFailureType) {
                case NETWORK_ERROR -> GenerationErrorCode.AI_PROVIDER_CONNECTION_ERROR;
                case TRANSIENT_SERVER_ERROR -> GenerationErrorCode.AI_PROVIDER_SERVER_ERROR;
                case AUTH_CONFIGURATION_ERROR -> GenerationErrorCode.AI_PROVIDER_CONFIGURATION_ERROR;
                case REQUEST_REJECTED -> GenerationErrorCode.AI_PROVIDER_REQUEST_REJECTED;
                case CANCELLED -> GenerationErrorCode.GENERATION_INTERRUPTED;
                case UNKNOWN -> GenerationErrorCode.AI_PROVIDER_ERROR;
            };
            case RATE_LIMITED -> GenerationErrorCode.AI_PROVIDER_RATE_LIMITED;
            case TIMEOUT -> GenerationErrorCode.AI_PROVIDER_TIMEOUT;
            case CONTENT_BLOCKED -> GenerationErrorCode.AI_CONTENT_BLOCKED;
            case OUTPUT_TRUNCATED -> GenerationErrorCode.AI_OUTPUT_TRUNCATED;
            case RESPONSE_PROCESSING_ERROR -> GenerationErrorCode.AI_RESPONSE_PROCESSING_ERROR;
            case INTERNAL_ERROR -> GenerationErrorCode.GENERATION_INTERNAL_ERROR;
        };
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

    private static void validateProviderFailureType(AiProviderFailureType providerFailureType) {
        if (providerFailureType == null) {
            throw new IllegalArgumentException("AI 제공자 오류 상세 유형은 필수입니다.");
        }
    }
}
