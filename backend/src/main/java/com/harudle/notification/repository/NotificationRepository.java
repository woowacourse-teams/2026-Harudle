package com.harudle.notification.repository;

import com.harudle.notification.query.NotificationCursor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository {
    boolean isActiveMember(UUID userId);

    List<NotificationSnapshot> findAfter(UUID recipientId, Optional<NotificationCursor> cursor, int limit);

    long countUnread(UUID recipientId);

    /** 공개 중인 부모 피드·일기의 알림만 반환한다. 소유자 검증은 서비스에서 수행한다. */
    Optional<NotificationReadTarget> findVisibleById(UUID notificationId);

    /** 최초 read_at만 기록한다. 반복 요청도 보이는 본인 알림이면 true다. */
    boolean markRead(UUID notificationId, UUID recipientId, Instant readAt);
}
