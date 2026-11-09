package com.harudle.campus.application;

import java.io.Serial;

public final class CampusLinkDisabledException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CampusLinkDisabledException() {
        super("캠퍼스 연결 기능이 꺼져 있습니다.");
    }
}
