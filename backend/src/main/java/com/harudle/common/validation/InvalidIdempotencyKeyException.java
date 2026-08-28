package com.harudle.common.validation;

import java.io.Serial;

public final class InvalidIdempotencyKeyException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    InvalidIdempotencyKeyException() {
        super("Idempotency-Key는 UUID 형식의 필수 헤더입니다.");
    }
}
