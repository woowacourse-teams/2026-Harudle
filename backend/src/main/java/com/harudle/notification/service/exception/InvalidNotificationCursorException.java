package com.harudle.notification.service.exception;

public class InvalidNotificationCursorException extends RuntimeException {
    public InvalidNotificationCursorException() {
        super("알림 커서가 올바르지 않거나 조회자와 일치하지 않습니다.");
    }
}
