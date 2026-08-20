package com.harudle.common.error;

import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.service.exception.AiGenerationErrorType;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;

public enum ErrorType {

    VALIDATION_ERROR(
            HttpStatus.BAD_REQUEST,
            "Validation failed",
            "요청 값이 올바르지 않습니다."
    ),
    INVALID_IDEMPOTENCY_KEY(
            HttpStatus.BAD_REQUEST,
            "Invalid idempotency key",
            "Idempotency-Key는 UUID 형식의 필수 헤더입니다."
    ),
    UNAUTHORIZED(
            HttpStatus.UNAUTHORIZED,
            "Unauthorized",
            "인증 정보가 필요합니다."
    ),
    INVALID_REFRESH_TOKEN(
            HttpStatus.UNAUTHORIZED,
            "Invalid refresh token",
            "Refresh Token이 유효하지 않습니다."
    ),
    INVALID_CURRENT_USER(
            HttpStatus.UNAUTHORIZED,
            "Invalid current user",
            "현재 로그인 사용자를 확인할 수 없습니다."
    ),
    FORBIDDEN(
            HttpStatus.FORBIDDEN,
            "Forbidden",
            "해당 리소스에 접근할 권한이 없습니다."
    ),
    DIARY_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "Diary not found",
            "일기를 찾을 수 없습니다."
    ),
    SHARE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "Share not found",
            "공유 링크를 찾을 수 없습니다."
    ),
    GENERATION_IN_PROGRESS(
            HttpStatus.CONFLICT,
            "Generation in progress",
            "동일한 만화 생성 요청이 처리 중입니다."
    ),
    GENERATION_FAILED(
            HttpStatus.CONFLICT,
            "Generation failed",
            "공유할 그림일기 생성 결과가 없습니다."
    ),
    IDEMPOTENCY_KEY_CONFLICT(
            HttpStatus.CONFLICT,
            "Idempotency key conflict",
            "동일한 멱등성 키가 다른 요청에 사용되었습니다."
    ),
    DAILY_GENERATION_LIMIT_EXCEEDED(
            HttpStatus.TOO_MANY_REQUESTS,
            "Daily generation limit exceeded",
            "오늘 생성 가능한 횟수를 모두 사용했습니다."
    ),
    AI_PROVIDER_ERROR(
            HttpStatus.BAD_GATEWAY,
            "AI provider error",
            "AI 공급자 호출에 실패했습니다."
    ),
    GENERATION_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Generation unavailable",
            "생성 기능을 사용할 수 없습니다."
    ),
    GENERATION_INTERRUPTED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Generation interrupted",
            "생성 처리가 완료되지 못했습니다."
    ),
    IMAGE_STORAGE_ERROR(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Image storage error",
            "생성 이미지를 저장하거나 불러오지 못했습니다."
    ),
    AI_PROVIDER_TIMEOUT(
            HttpStatus.GATEWAY_TIMEOUT,
            "AI provider timeout",
            "AI 공급자 응답 시간이 초과되었습니다."
    ),
    INTERNAL_SERVER_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal server error",
            "서버에서 요청을 처리하지 못했습니다."
    );

    private static final String PROBLEM_TYPE_URN_PREFIX = "urn:harudle:problem:";

    private final HttpStatus status;
    private final String title;
    private final String detail;

    ErrorType(HttpStatus status, String title, String detail) {
        this.status = status;
        this.title = title;
        this.detail = detail;
    }

    public HttpStatus status() {
        return status;
    }

    String title() {
        return title;
    }

    String detail() {
        return detail;
    }

    String code() {
        return name();
    }

    URI problemType() {
        return problemType(code());
    }

    static URI problemType(String code) {
        String slug = code.toLowerCase(Locale.ROOT).replace('_', '-');
        return URI.create(PROBLEM_TYPE_URN_PREFIX + slug);
    }

    static ErrorType from(GenerationErrorCode errorCode) {
        return switch (errorCode) {
            case AI_PROVIDER_ERROR -> AI_PROVIDER_ERROR;
            case AI_PROVIDER_TIMEOUT -> AI_PROVIDER_TIMEOUT;
            case GENERATION_INTERRUPTED -> GENERATION_INTERRUPTED;
            case IMAGE_STORAGE_ERROR -> IMAGE_STORAGE_ERROR;
        };
    }

    static ErrorType from(AiGenerationErrorType errorType) {
        return switch (errorType) {
            case PROVIDER_ERROR -> AI_PROVIDER_ERROR;
            case TIMEOUT -> AI_PROVIDER_TIMEOUT;
        };
    }
}
