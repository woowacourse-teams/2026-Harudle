package com.harudle.moderation.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ModerationEvent(UUID id, ModerationTargetType targetType, UUID targetId,
        boolean restricted, long version, String reason, @Nullable UUID actorId, Instant changedAt) {
}
