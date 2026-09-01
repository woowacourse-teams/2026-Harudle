package com.harudle.admin.service.exception;

import java.io.Serial;

public final class AdminGenerationLimitBelowUsageException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public AdminGenerationLimitBelowUsageException() {
        super("현재 사용량보다 작은 생성 한도로 변경할 수 없습니다.");
    }
}
