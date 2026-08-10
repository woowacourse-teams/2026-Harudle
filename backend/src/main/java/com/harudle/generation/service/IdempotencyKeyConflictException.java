package com.harudle.generation.service;

import java.io.Serial;

public final class IdempotencyKeyConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public IdempotencyKeyConflictException() {
        super("동일한 멱등성 키가 다른 요청에 사용되었습니다.");
    }
}
