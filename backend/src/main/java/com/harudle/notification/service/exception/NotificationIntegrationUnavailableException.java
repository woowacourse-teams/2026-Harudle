package com.harudle.notification.service.exception;

import java.io.Serial;

/** 활성 행위자의 프로필 미연동이나 누락을 가짜 프로필로 대체하지 않는다. */
public final class NotificationIntegrationUnavailableException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public NotificationIntegrationUnavailableException() {
        super("알림의 회원 정보를 조회할 수 없습니다.");
    }
}
