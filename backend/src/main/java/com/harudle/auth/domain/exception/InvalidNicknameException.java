package com.harudle.auth.domain.exception;

import java.io.Serial;

public final class InvalidNicknameException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidNicknameException(String message) {
        super(message);
    }
}
