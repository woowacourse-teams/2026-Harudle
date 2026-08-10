package com.harudle.common.error;

import com.harudle.generation.domain.GenerationUsage;
import org.springframework.http.HttpStatus;

public enum ErrorType {

    VALIDATION_ERROR(
            HttpStatus.BAD_REQUEST,
            "VALIDATION_ERROR",
            "validation-error",
            "Validation failed",
            "요청 값이 올바르지 않습니다."
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
    DAILY_GENERATION_LIMIT_EXCEEDED(
            HttpStatus.TOO_MANY_REQUESTS,
            "DAILY_GENERATION_LIMIT_EXCEEDED",
            "daily-generation-limit-exceeded",
            "Daily generation limit exceeded",
            "하루 최대 %d번까지 생성할 수 있습니다."
                    .formatted(GenerationUsage.DEFAULT_LIMIT_COUNT)
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
}
