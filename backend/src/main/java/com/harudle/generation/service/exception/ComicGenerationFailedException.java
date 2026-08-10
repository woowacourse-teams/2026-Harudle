package com.harudle.generation.service.exception;

import java.io.Serial;

import com.harudle.generation.domain.GenerationErrorCode;

public final class ComicGenerationFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final GenerationErrorCode errorCode;

    public ComicGenerationFailedException(GenerationErrorCode errorCode) {
        super("기존 만화 생성 작업이 실패했습니다.");
        validateErrorCode(errorCode);
        this.errorCode = errorCode;
    }

    public GenerationErrorCode getErrorCode() {
        return errorCode;
    }

    private static void validateErrorCode(GenerationErrorCode errorCode) {
        if (errorCode == null) {
            throw new IllegalArgumentException("생성 오류 코드는 필수입니다.");
        }
    }
}
