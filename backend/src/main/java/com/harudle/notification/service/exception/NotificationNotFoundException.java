package com.harudle.notification.service.exception;

import java.io.Serial;

public final class NotificationNotFoundException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public NotificationNotFoundException() {
        super("알림을 찾을 수 없습니다.");
    }
}
