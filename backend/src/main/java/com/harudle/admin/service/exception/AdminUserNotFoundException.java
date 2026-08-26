package com.harudle.admin.service.exception;

import java.io.Serial;

public final class AdminUserNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public AdminUserNotFoundException() {
        super("사용자를 찾을 수 없습니다.");
    }
}
