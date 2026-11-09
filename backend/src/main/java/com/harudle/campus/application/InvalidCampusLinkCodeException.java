package com.harudle.campus.application;

import java.io.Serial;

public final class InvalidCampusLinkCodeException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidCampusLinkCodeException() {
        super("캠퍼스 연결 코드가 유효하지 않습니다.");
    }
}
