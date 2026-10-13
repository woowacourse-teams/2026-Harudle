package com.harudle.comment.service.exception;

import java.io.Serial;

public final class CommentNotFoundException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public CommentNotFoundException() {
        super("댓글을 찾을 수 없습니다.");
    }
}
