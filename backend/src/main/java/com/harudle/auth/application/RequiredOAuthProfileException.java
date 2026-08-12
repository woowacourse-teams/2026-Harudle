package com.harudle.auth.application;

public final class RequiredOAuthProfileException extends RuntimeException {

    public RequiredOAuthProfileException(String message) {
        super(message);
    }

}
