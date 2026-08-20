package com.harudle.guest.application.exception;

import java.io.Serial;

public final class GuestTrialAlreadyUsedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public GuestTrialAlreadyUsedException() {
        super("로그인 전 무료 사용 기회를 이미 사용했습니다.");
    }
}
