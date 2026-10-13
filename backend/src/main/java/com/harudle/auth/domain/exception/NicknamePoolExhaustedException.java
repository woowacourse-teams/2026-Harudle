package com.harudle.auth.domain.exception;

public class NicknamePoolExhaustedException extends RuntimeException {

    public NicknamePoolExhaustedException() {
        super("배정 가능한 닉네임 후보가 모두 소진되었습니다.");
    }
}
