package com.harudle.feed.service.exception;

import java.io.Serial;

public final class InvalidFeedCursorException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidFeedCursorException() {
        super("페이지 커서가 올바르지 않거나 조회 조건과 일치하지 않습니다.");
    }
}
