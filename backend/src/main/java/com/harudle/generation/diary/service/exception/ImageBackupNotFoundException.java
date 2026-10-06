package com.harudle.generation.diary.service.exception;

import java.io.Serial;

public final class ImageBackupNotFoundException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ImageBackupNotFoundException() {
        super("이미지 원본 백업을 찾을 수 없습니다.");
    }
}
