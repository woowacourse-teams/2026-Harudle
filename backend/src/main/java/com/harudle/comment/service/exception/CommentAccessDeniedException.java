package com.harudle.comment.service.exception;

import java.io.Serial;

public final class CommentAccessDeniedException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public CommentAccessDeniedException() {
        super("댓글을 삭제할 권한이 없습니다.");
    }
}
