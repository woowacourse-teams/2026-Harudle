package com.harudle.block.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 저장은 단방향이며 조회의 상호 숨김은 양방향 존재 여부로 판단한다. */
@Entity
@Table(name = "user_blocks")
public class UserBlock {
    @Id
    private UUID id;
    @Column(name = "blocker_id", nullable = false, updatable = false)
    private UUID blockerId;
    @Column(name = "blocked_id", nullable = false, updatable = false)
    private UUID blockedId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserBlock() {
    }

    public UserBlock(UUID blockerId, UUID blockedId, Instant createdAt) {
        if (blockerId == null || blockedId == null || createdAt == null || blockerId.equals(blockedId)) {
            throw new InvalidBlockException();
        }
        this.id = UUID.randomUUID();
        this.blockerId = blockerId;
        this.blockedId = blockedId;
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public UUID getBlockerId() {
        return blockerId;
    }

    public UUID getBlockedId() {
        return blockedId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
