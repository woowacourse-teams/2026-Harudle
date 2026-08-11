package com.harudle.common.error;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;

public enum ErrorType {

    VALIDATION_ERROR(
            HttpStatus.BAD_REQUEST,
            "Validation failed",
            "요청 값이 올바르지 않습니다."
    ),
    UNAUTHORIZED(
            HttpStatus.UNAUTHORIZED,
            "Unauthorized",
            "인증 정보가 필요합니다."
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
    DAILY_GENERATION_LIMIT_EXCEEDED(
            HttpStatus.TOO_MANY_REQUESTS,
            "Daily generation limit exceeded",
            "오늘 생성 가능한 횟수를 모두 사용했습니다."
    ),
    IMAGE_STORAGE_ERROR(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Image storage error",
            "생성 이미지를 저장하거나 불러오지 못했습니다."
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
        String slug = name().toLowerCase(Locale.ROOT).replace('_', '-');
        return URI.create(PROBLEM_TYPE_URN_PREFIX + slug);
    }
}
