package com.harudle.comment.service.exception;

import java.io.Serial;

/** 공개 프로필 미연동 또는 누락을 가짜 작성자 정보로 대체하지 않는다. */
public final class CommentIntegrationUnavailableException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public CommentIntegrationUnavailableException() {
        super("댓글 작성자 정보를 조회할 수 없습니다.");
    }
}
