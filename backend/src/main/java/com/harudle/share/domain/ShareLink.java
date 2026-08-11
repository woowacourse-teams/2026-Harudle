package com.harudle.share.domain;

import java.time.Instant;
import java.util.UUID;

public class ShareLink {

    private final UUID id;
    private final UUID generationId;
    private final Instant createdAt;

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
