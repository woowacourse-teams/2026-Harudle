package com.harudle.share.service.exception;

import java.io.Serial;

public final class ShareNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ShareNotFoundException() {
        super("공유 링크를 찾을 수 없습니다.");
    }
}
