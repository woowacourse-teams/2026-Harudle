package com.harudle.feed.service.exception;

import java.io.Serial;

public final class FeedAccessDeniedException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public FeedAccessDeniedException() {
        super("본인이 작성한 피드만 삭제할 수 있습니다.");
    }
}
