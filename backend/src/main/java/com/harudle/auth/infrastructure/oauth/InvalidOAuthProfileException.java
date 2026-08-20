package com.harudle.auth.infrastructure.oauth;

public final class InvalidOAuthProfileException extends RuntimeException {

    private static final String MESSAGE = "OAuth 공급자 프로필이 올바르지 않습니다.";

    public InvalidOAuthProfileException() {
        super(MESSAGE);
    }

    public InvalidOAuthProfileException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
