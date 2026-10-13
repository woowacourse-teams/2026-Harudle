package com.harudle.notification.query;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 수신자별 최신순 목록의 다음 경계. 알림 행 자체가 삭제되어도 경계 값으로 이어간다. */
public record NotificationCursor(UUID recipientId, Instant createdAt, UUID id) {
    public NotificationCursor {
        Objects.requireNonNull(recipientId, "recipientId는 필수입니다.");
        Objects.requireNonNull(createdAt, "createdAt은 필수입니다.");
        Objects.requireNonNull(id, "id는 필수입니다.");
    }
}
