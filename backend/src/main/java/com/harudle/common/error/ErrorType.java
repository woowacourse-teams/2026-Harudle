package com.harudle.common.error;

import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.service.exception.AiGenerationErrorType;
import org.springframework.http.HttpStatus;

public enum ErrorType {

    VALIDATION_ERROR(
            HttpStatus.BAD_REQUEST,
            "VALIDATION_ERROR",
            "validation-error",
            "Validation failed",
            "요청 값이 올바르지 않습니다."
    ),
    INVALID_IDEMPOTENCY_KEY(
            HttpStatus.BAD_REQUEST,
            "INVALID_IDEMPOTENCY_KEY",
            "invalid-idempotency-key",
            "Invalid idempotency key",
            "Idempotency-Key는 UUID 형식의 필수 헤더입니다."
    ),
    UNAUTHORIZED(
            HttpStatus.UNAUTHORIZED,
            "UNAUTHORIZED",
            "unauthorized",
            "Unauthorized",
            "인증 정보가 필요합니다."
    ),
    FORBIDDEN(
            HttpStatus.FORBIDDEN,
            "FORBIDDEN",
            "forbidden",
            "Forbidden",
            "해당 리소스에 접근할 권한이 없습니다."
    ),
    DIARY_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "DIARY_NOT_FOUND",
            "diary-not-found",
            "Diary not found",
            "일기를 찾을 수 없습니다."
    ),
    GENERATION_IN_PROGRESS(
            HttpStatus.CONFLICT,
            "GENERATION_IN_PROGRESS",
            "generation-in-progress",
            "Generation in progress",
            "동일한 만화 생성 요청이 처리 중입니다."
    ),
    IDEMPOTENCY_KEY_CONFLICT(
            HttpStatus.CONFLICT,
            "IDEMPOTENCY_KEY_CONFLICT",
            "idempotency-key-conflict",
            "Idempotency key conflict",
            "동일한 멱등성 키가 다른 요청에 사용되었습니다."
    ),
    DAILY_GENERATION_LIMIT_EXCEEDED(
            HttpStatus.TOO_MANY_REQUESTS,
            "DAILY_GENERATION_LIMIT_EXCEEDED",
            "daily-generation-limit-exceeded",
            "Daily generation limit exceeded",
            "하루 최대 3번까지 생성할 수 있습니다."
    ),
    AI_PROVIDER_ERROR(
            HttpStatus.BAD_GATEWAY,
            "AI_PROVIDER_ERROR",
            "ai-provider-error",
            "AI provider error",
            "AI 공급자 호출에 실패했습니다."
    ),
    GENERATION_INTERRUPTED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "GENERATION_INTERRUPTED",
            "generation-interrupted",
            "Generation interrupted",
            "생성 처리가 완료되지 못했습니다."
    ),
    IMAGE_STORAGE_ERROR(
            HttpStatus.SERVICE_UNAVAILABLE,
            "IMAGE_STORAGE_ERROR",
            "image-storage-error",
            "Image storage error",
            "생성 이미지를 저장하거나 불러오지 못했습니다."
    ),
    AI_PROVIDER_TIMEOUT(
            HttpStatus.GATEWAY_TIMEOUT,
            "AI_PROVIDER_TIMEOUT",
            "ai-provider-timeout",
            "AI provider timeout",
            "AI 공급자 응답 시간이 초과되었습니다."
    ),
    INTERNAL_SERVER_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_SERVER_ERROR",
            "internal-server-error",
            "Internal server error",
            "서버에서 요청을 처리하지 못했습니다."
    );

    private final HttpStatus status;
    private final String code;
    private final String typeSlug;
    private final String title;
    private final String detail;

    ErrorType(HttpStatus status, String code, String typeSlug, String title, String detail) {
        this.status = status;
        this.code = code;
        this.typeSlug = typeSlug;
        this.title = title;
        this.detail = detail;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getTypeSlug() {
        return typeSlug;
    }

    public String getTitle() {
        return title;
    }

    public String getDetail() {
        return detail;
    }

    public static ErrorType from(GenerationErrorCode errorCode) {
        return switch (errorCode) {
            case AI_PROVIDER_ERROR -> AI_PROVIDER_ERROR;
            case AI_PROVIDER_TIMEOUT -> AI_PROVIDER_TIMEOUT;
            case GENERATION_INTERRUPTED -> GENERATION_INTERRUPTED;
            case IMAGE_STORAGE_ERROR -> IMAGE_STORAGE_ERROR;
        };
    }

    public static ErrorType from(AiGenerationErrorType errorType) {
        return switch (errorType) {
            case PROVIDER_ERROR -> AI_PROVIDER_ERROR;
            case TIMEOUT -> AI_PROVIDER_TIMEOUT;
        };
    }
}
