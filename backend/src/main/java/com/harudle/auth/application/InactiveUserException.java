package com.harudle.auth.application;

public final class InactiveUserException extends RuntimeException {

    public InactiveUserException() {
        super("탈퇴하거나 비활성화된 사용자입니다.");
    }

}
