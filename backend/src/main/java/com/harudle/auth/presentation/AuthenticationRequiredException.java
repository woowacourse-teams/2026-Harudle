package com.harudle.auth.presentation;

import java.io.Serial;

public final class AuthenticationRequiredException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    AuthenticationRequiredException() {
        super("인증 정보가 필요합니다.");
    }
}
