package com.harudle.generation.usage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "daily_generation_usage")
public class DailyGenerationUsage {

    @EmbeddedId
    private GenerationUsageId id;

    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @Column(name = "limit_count", nullable = false)
    private int limitCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DailyGenerationUsage() {
    }
}
