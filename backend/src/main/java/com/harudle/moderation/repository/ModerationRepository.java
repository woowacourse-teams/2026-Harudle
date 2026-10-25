package com.harudle.moderation.repository;

import com.harudle.moderation.domain.ModerationEvent;
import com.harudle.moderation.domain.ModerationState;
import com.harudle.moderation.domain.ModerationTargetType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ModerationRepository {
    boolean isActiveAdmin(UUID actorId);
    boolean targetExists(ModerationTargetType type, UUID targetId);
    boolean lockManageableTarget(ModerationTargetType type, UUID targetId);
    Optional<ModerationState> find(ModerationTargetType type, UUID targetId);
    ModerationState lockOrCreate(ModerationTargetType type, UUID targetId);
    void saveChange(ModerationState state);
    List<ModerationEvent> history(ModerationTargetType type, UUID targetId, int offset, int limit);
    long countHistory(ModerationTargetType type, UUID targetId);
}
