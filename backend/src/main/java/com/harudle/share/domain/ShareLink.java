package com.harudle.share.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "share_links")
public class ShareLink {

    @Id
    private UUID id;

    @Column(name = "generation_id", nullable = false, unique = true)
    private UUID generationId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ShareLink() {
    }

    private ShareLink(UUID id, UUID generationId) {
        validateId(id);
        validateGenerationId(generationId);
        this.id = id;
        this.generationId = generationId;
    }

    public static ShareLink create(UUID generationId) {
        return new ShareLink(UUID.randomUUID(), generationId);
    }

    private static void validateId(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("공유 링크 ID는 필수입니다.");
        }
    }

    private static void validateGenerationId(UUID generationId) {
        if (generationId == null) {
            throw new IllegalArgumentException("만화 생성 ID는 필수입니다.");
        }
    }
}
