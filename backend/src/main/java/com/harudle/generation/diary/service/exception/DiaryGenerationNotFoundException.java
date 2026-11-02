package com.harudle.generation.diary.service.exception;

import java.io.Serial;

public final class DiaryGenerationNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public DiaryGenerationNotFoundException() {
        super("생성 작업을 찾을 수 없습니다.");
    }
}
