package com.harudle.moderation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "moderation_states")
@IdClass(ModerationKey.class)
public class ModerationRestriction {
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 20)
    private ModerationTargetType targetType;
    @Id
    @Column(name = "target_id")
    private UUID targetId;
    @Column(name = "restricted", nullable = false)
    private boolean restricted;

    protected ModerationRestriction() { }
}
