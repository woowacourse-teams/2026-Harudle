package com.harudle.diary.service.exception;

import java.io.Serial;

public final class DiaryNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public DiaryNotFoundException() {
        super("일기를 찾을 수 없습니다.");
    }
}
