package com.harudle.moderation.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ModerationState(ModerationTargetType targetType, UUID targetId, boolean restricted,
        long version, @Nullable String reason, @Nullable UUID actorId, @Nullable Instant changedAt) {
    public static ModerationState initial(ModerationTargetType type, UUID id) {
        return new ModerationState(type, id, false, 0, null, null, null);
    }

    public boolean isReplay(ModerationChange change, UUID actor) {
        return version == change.expectedVersion() + 1
                && restricted == change.restricted() && actor.equals(actorId) && change.reason().equals(reason);
    }

    public ModerationState changed(ModerationChange change, UUID actor, Instant now) {
        return new ModerationState(targetType, targetId, change.restricted(), version + 1,
                change.reason(), actor, now);
    }
}
