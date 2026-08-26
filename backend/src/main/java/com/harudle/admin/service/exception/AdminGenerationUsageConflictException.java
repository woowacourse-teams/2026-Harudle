package com.harudle.admin.service.exception;

import java.io.Serial;

public final class AdminGenerationUsageConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public AdminGenerationUsageConflictException() {
        super("현재 생성 사용량을 기준으로 요청을 다시 확인해 주세요.");
    }
}
