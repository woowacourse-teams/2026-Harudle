package com.harudle.generation.diary.service.exception;

import com.harudle.generation.diary.domain.GenerationErrorCode;
import java.io.Serial;

public final class DiaryGenerationFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final GenerationErrorCode errorCode;
    private final boolean guest;

    public DiaryGenerationFailedException(GenerationErrorCode errorCode) {
        this(errorCode, false);
    }

    public DiaryGenerationFailedException(GenerationErrorCode errorCode, boolean guest) {
        super("기존 그림일기 생성 작업이 실패했습니다.");
        validateErrorCode(errorCode);
        this.errorCode = errorCode;
        this.guest = guest;
    }

    public GenerationErrorCode errorCode() {
        return errorCode;
    }

    public boolean guest() {
        return guest;
    }

    private static void validateErrorCode(GenerationErrorCode errorCode) {
        if (errorCode == null) {
            throw new IllegalArgumentException("생성 오류 코드는 필수입니다.");
        }
    }
}
