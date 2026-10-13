package com.harudle.notification.service.exception;

import java.io.Serial;

public final class NotificationAccessDeniedException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public NotificationAccessDeniedException() {
        super("알림을 읽을 권한이 없습니다.");
    }
}
