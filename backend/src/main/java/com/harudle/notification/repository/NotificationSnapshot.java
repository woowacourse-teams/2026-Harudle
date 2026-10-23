package com.harudle.notification.repository;

import com.harudle.notification.service.port.InAppNotificationRecorder.Type;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** 탈퇴한 행위자의 ID는 노출하지 않는다. 알림 이력과 읽음 상태는 유지한다. */
public record NotificationSnapshot(
        UUID id, UUID recipientId, @Nullable UUID actorId, UUID feedId,
        Type type, Instant createdAt, @Nullable Instant readAt
) {}
