package com.harudle.feed.service.exception;

import java.io.Serial;

public final class FeedPreviewCapacityExceededException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public FeedPreviewCapacityExceededException() {
        super("공유 미리보기 이미지를 처리 중입니다. 잠시 후 다시 시도해 주세요.");
    }
}
