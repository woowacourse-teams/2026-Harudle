package com.harudle.feed.service.exception;

import java.io.Serial;

public final class DiaryAlreadyPublishedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public DiaryAlreadyPublishedException() {
        super("이미 피드로 게시된 일기입니다.");
    }
}
