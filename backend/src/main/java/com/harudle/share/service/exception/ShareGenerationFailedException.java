package com.harudle.share.service.exception;

import java.io.Serial;

public final class ShareGenerationFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ShareGenerationFailedException() {
        super("실패한 그림일기 생성 결과는 공유할 수 없습니다.");
    }
}
