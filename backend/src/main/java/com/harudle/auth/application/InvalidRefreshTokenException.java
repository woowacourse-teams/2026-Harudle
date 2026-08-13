package com.harudle.auth.application;

public final class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("유효하지 않은 Refresh Token입니다.");
    }

}
