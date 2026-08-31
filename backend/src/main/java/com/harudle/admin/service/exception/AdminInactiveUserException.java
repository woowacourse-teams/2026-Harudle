package com.harudle.admin.service.exception;

import java.io.Serial;

public final class AdminInactiveUserException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public AdminInactiveUserException() {
        super("탈퇴한 사용자는 관리자 변경 작업의 대상이 될 수 없습니다.");
    }
}
