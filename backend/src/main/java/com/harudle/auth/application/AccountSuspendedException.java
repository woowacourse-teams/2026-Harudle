package com.harudle.auth.application;

public final class AccountSuspendedException extends RuntimeException {
    public AccountSuspendedException() {
        super("사용이 제한된 계정입니다.");
    }
}
