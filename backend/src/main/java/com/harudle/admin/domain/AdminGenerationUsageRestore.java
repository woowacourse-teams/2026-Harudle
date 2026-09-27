package com.harudle.admin.domain;

import com.harudle.generation.usage.domain.GenerationUsage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "admin_generation_usage_restores")
public class AdminGenerationUsageRestore {

    @Id
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "restore_count", nullable = false, updatable = false)
    private int restoreCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AdminGenerationUsageRestoreStatus status;

    @Column(name = "usage_date")
    private LocalDate usageDate;

    @Column(name = "used_count")
    private Integer usedCount;

    @Column(name = "limit_count")
    private Integer limitCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AdminGenerationUsageRestore() {
    }

    public boolean matches(UUID userId, int restoreCount) {
        return this.userId.equals(userId) && this.restoreCount == restoreCount;
    }

    public boolean isSucceeded() {
        return status == AdminGenerationUsageRestoreStatus.SUCCEEDED;
    }

    public boolean isConflict() {
        return status == AdminGenerationUsageRestoreStatus.CONFLICT;
    }

    public GenerationUsage toGenerationUsage() {
        if (!isSucceeded() || usageDate == null || usedCount == null || limitCount == null) {
            throw new IllegalStateException("성공한 사용량 복구 기록만 결과로 변환할 수 있습니다.");
        }
        return new GenerationUsage(usageDate, usedCount, limitCount);
    }

    public void complete(GenerationUsage usage) {
        if (status != AdminGenerationUsageRestoreStatus.PROCESSING) {
            throw new IllegalStateException("처리 중인 사용량 복구 기록만 완료할 수 있습니다.");
        }
        if (usage == null) {
            throw new IllegalArgumentException("사용량 복구 결과는 필수입니다.");
        }
        this.usageDate = usage.usageDate();
        this.usedCount = usage.usedCount();
        this.limitCount = usage.limitCount();
        this.status = AdminGenerationUsageRestoreStatus.SUCCEEDED;
    }

    public void markConflict() {
        if (status != AdminGenerationUsageRestoreStatus.PROCESSING) {
            throw new IllegalStateException("처리 중인 사용량 복구 기록만 충돌 상태로 변경할 수 있습니다.");
        }
        this.usageDate = null;
        this.usedCount = null;
        this.limitCount = null;
        this.status = AdminGenerationUsageRestoreStatus.CONFLICT;
    }
}
