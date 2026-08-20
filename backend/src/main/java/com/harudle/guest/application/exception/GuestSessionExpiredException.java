package com.harudle.guest.application.exception;

import java.io.Serial;

public final class GuestSessionExpiredException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public GuestSessionExpiredException() {
        super("게스트 세션이 만료되었습니다.");
    }
}
