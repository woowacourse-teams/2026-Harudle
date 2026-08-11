package com.harudle.share.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "share_links")
public class ShareLink {

    @Id
    private UUID id;

    @Column(name = "generation_id", nullable = false, unique = true)
    private UUID generationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ShareLink() {
    }

    private ShareLink(UUID id, UUID generationId, Instant createdAt) {
        validateGenerationId(generationId);

        this.id = id;
        this.generationId = generationId;
        this.createdAt = createdAt;
    }

    public static ShareLink create(UUID generationId, Instant createdAt) {
        return new ShareLink(UUID.randomUUID(), generationId, createdAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getGenerationId() {
        return generationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private static void validateGenerationId(UUID generationId) {
        if (generationId == null) {
            throw new IllegalArgumentException("생성 ID는 필수입니다.");
        }
    }
}
