package com.harudle.campus.application;

import java.io.Serial;

public final class InvalidCampusClientException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidCampusClientException() {
        super("캠퍼스 클라이언트 인증에 실패했습니다.");
    }
}
