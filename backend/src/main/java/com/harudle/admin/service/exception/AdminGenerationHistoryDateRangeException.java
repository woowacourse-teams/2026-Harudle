package com.harudle.admin.service.exception;

public final class AdminGenerationHistoryDateRangeException extends RuntimeException {

    public AdminGenerationHistoryDateRangeException() {
        super("생성 이력 조회 시작일은 종료일보다 늦을 수 없습니다.");
    }
}
