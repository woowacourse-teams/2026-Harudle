package com.harudle.admin.service.exception;

public final class AdminGenerationHistoryDateRangeException extends RuntimeException {

    public AdminGenerationHistoryDateRangeException() {
        super("생성 이력 조회 날짜 범위가 올바르지 않습니다.");
    }
}
