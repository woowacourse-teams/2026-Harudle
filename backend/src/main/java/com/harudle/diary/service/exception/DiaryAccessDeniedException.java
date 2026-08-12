package com.harudle.diary.service.exception;

import java.io.Serial;

public final class DiaryAccessDeniedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public DiaryAccessDeniedException() {
        super("다른 사용자의 일기에 접근할 수 없습니다.");
    }
}
