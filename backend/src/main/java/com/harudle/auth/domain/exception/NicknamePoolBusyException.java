package com.harudle.auth.domain.exception;

public class NicknamePoolBusyException extends RuntimeException {

    public NicknamePoolBusyException() {
        super("사용 가능한 닉네임 후보를 다른 요청이 확보하고 있습니다.");
    }
}
