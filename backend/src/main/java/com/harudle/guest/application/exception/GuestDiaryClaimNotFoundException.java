package com.harudle.guest.application.exception;

import java.io.Serial;

public final class GuestDiaryClaimNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public GuestDiaryClaimNotFoundException() {
        super("가져올 수 있는 체험 일기를 찾을 수 없습니다.");
    }
}
