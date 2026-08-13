package com.harudle.auth.application;

public final class InvalidCurrentUserException extends RuntimeException {

    public InvalidCurrentUserException() {
        super("현재 로그인 사용자를 확인할 수 없습니다.");
    }

}
