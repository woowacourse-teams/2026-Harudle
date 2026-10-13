package com.harudle.auth.domain.exception;

import java.io.Serial;

public final class PublicProfileNotAssignedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public PublicProfileNotAssignedException() {
        super("공개 프로필을 먼저 배정해야 합니다.");
    }
}
