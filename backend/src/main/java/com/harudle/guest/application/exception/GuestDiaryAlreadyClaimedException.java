package com.harudle.guest.application.exception;

import java.io.Serial;

public final class GuestDiaryAlreadyClaimedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public GuestDiaryAlreadyClaimedException() {
        super("다른 계정에서 이미 가져간 체험 일기입니다.");
    }
}
