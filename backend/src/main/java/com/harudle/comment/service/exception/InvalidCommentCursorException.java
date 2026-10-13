package com.harudle.comment.service.exception;

import java.io.Serial;

public final class InvalidCommentCursorException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidCommentCursorException() {
        super("댓글 조회 커서가 올바르지 않습니다.");
    }
}
