package com.harudle.diary.service.exception;

import java.io.Serial;

public final class DiaryNotPublishableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public DiaryNotPublishableException() {
        super("이미지 생성이 완료된 일기만 게시할 수 있습니다.");
    }
}
