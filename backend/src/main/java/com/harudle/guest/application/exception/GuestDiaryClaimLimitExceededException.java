package com.harudle.guest.application.exception;

import java.io.Serial;

public final class GuestDiaryClaimLimitExceededException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public GuestDiaryClaimLimitExceededException() {
        super("체험 일기는 계정당 한 번만 가져올 수 있습니다.");
    }

    public GuestDiaryClaimLimitExceededException(Throwable cause) {
        super("체험 일기는 계정당 한 번만 가져올 수 있습니다.", cause);
    }
}
