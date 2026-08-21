package com.harudle.guest.application.exception;

import java.io.Serial;

public final class GuestSessionRequiredException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public GuestSessionRequiredException() {
        super("게스트 세션이 필요합니다.");
    }
}
