package com.harudle.generation.diary.service.port;

import java.io.Serial;

public final class ImageStorageException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ImageStorageException(String message) {
        this(message, null);
    }

    public ImageStorageException(String message, Throwable cause) {
        super(message, cause);
        validateMessage(message);
    }

    private static void validateMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("이미지 저장소 오류 메시지는 필수입니다.");
        }
    }
}
