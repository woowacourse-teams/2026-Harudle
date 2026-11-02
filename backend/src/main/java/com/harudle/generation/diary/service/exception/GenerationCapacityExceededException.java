package com.harudle.generation.diary.service.exception;

import java.io.Serial;

public final class GenerationCapacityExceededException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public GenerationCapacityExceededException() {
        super("현재 생성 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
    }
}
