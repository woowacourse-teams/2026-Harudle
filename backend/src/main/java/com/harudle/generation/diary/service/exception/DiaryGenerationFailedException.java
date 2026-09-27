package com.harudle.generation.diary.service.exception;

import com.harudle.generation.diary.domain.GenerationErrorCode;
import java.io.Serial;

public final class DiaryGenerationFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final GenerationErrorCode errorCode;

    public DiaryGenerationFailedException(GenerationErrorCode errorCode) {
        super("기존 그림일기 생성 작업이 실패했습니다.");
        validateErrorCode(errorCode);
        this.errorCode = errorCode;
    }

    public GenerationErrorCode errorCode() {
        return errorCode;
    }

    private static void validateErrorCode(GenerationErrorCode errorCode) {
        if (errorCode == null) {
            throw new IllegalArgumentException("생성 오류 코드는 필수입니다.");
        }
    }
}
