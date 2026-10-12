package com.harudle.notification.service.dto;

import com.harudle.notification.service.port.InAppNotificationRecorder.Type;
import com.harudle.profile.service.port.PublicProfileReader.Profile;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record NotificationResult(UUID id, Type type, UUID feedId, @Nullable Profile actor,
        String message, Instant createdAt, boolean read) {
    public NotificationResult {
        Objects.requireNonNull(id);
        Objects.requireNonNull(type);
        Objects.requireNonNull(feedId);
        Objects.requireNonNull(message);
        Objects.requireNonNull(createdAt);
    }
}
