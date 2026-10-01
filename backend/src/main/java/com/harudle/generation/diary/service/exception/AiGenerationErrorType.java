package com.harudle.generation.diary.service.exception;

public enum AiGenerationErrorType {

    PROVIDER_ERROR,
    RATE_LIMITED,
    TIMEOUT,
    OUTPUT_TRUNCATED,
    RESPONSE_PROCESSING_ERROR,
    INTERNAL_ERROR
}
