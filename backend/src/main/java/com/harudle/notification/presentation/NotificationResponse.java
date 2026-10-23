package com.harudle.notification.presentation;

import com.harudle.notification.service.dto.NotificationResult;
import com.harudle.notification.service.port.InAppNotificationRecorder.Type;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record NotificationResponse(UUID id, Type type, UUID feedId,
        @Schema(description = "행동한 회원의 공개 프로필. 탈퇴한 회원이면 null입니다.", nullable = true)
        @Nullable NotificationActorResponse actor,
        String message, Instant createdAt, boolean read) {
    static NotificationResponse from(NotificationResult notification) {
        return new NotificationResponse(notification.id(), notification.type(), notification.feedId(),
                notification.actor() == null ? null : NotificationActorResponse.from(notification.actor()),
                notification.message(), notification.createdAt(), notification.read());
    }
}
