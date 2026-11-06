package com.harudle.guest.application.exception;

import java.io.Serial;

public final class GuestDiaryClaimForbiddenException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public GuestDiaryClaimForbiddenException() {
        super("게스트 사용자 본인은 체험 일기를 가져갈 수 없습니다.");
    }
}
