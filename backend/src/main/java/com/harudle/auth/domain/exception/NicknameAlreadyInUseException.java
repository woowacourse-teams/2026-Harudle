package com.harudle.auth.domain.exception;

import java.io.Serial;

public final class NicknameAlreadyInUseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public NicknameAlreadyInUseException() {
        super("이미 사용 중인 닉네임입니다.");
    }

    public NicknameAlreadyInUseException(Throwable cause) {
        super("이미 사용 중인 닉네임입니다.", cause);
    }
}
