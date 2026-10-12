package com.harudle.feed.service.exception;

import java.io.Serial;

public final class FeedNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public FeedNotFoundException() {
        super("피드를 찾을 수 없습니다.");
    }
}
