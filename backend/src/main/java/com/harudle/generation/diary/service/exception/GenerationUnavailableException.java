package com.harudle.generation.diary.service.exception;

import java.io.Serial;

public final class GenerationUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private GenerationUnavailableException(String message) {
        super(message);
    }

    public static GenerationUnavailableException adaptersNotConfigured() {
        return new GenerationUnavailableException("AI 생성 어댑터가 구성되지 않았습니다.");
    }

    public static GenerationUnavailableException promptNotConfigured() {
        return new GenerationUnavailableException("사용할 생성 프롬프트가 없습니다.");
    }
}
