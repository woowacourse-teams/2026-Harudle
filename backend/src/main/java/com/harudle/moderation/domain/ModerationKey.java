package com.harudle.moderation.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class ModerationKey implements Serializable {
    private ModerationTargetType targetType;
    private UUID targetId;

    public ModerationKey() { }

    @Override
    public boolean equals(Object other) {
        return other instanceof ModerationKey key && targetType == key.targetType && Objects.equals(targetId, key.targetId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(targetType, targetId);
    }
}
