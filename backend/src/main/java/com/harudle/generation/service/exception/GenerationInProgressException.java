package com.harudle.generation.service.exception;

import java.io.Serial;

public final class GenerationInProgressException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public GenerationInProgressException() {
        super("동일한 그림일기 생성 요청이 처리 중입니다.");
    }
}
