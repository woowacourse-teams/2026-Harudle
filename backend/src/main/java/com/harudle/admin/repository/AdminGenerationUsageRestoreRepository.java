package com.harudle.admin.repository;

import com.harudle.admin.domain.AdminGenerationUsageRestore;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminGenerationUsageRestoreRepository extends
        JpaRepository<AdminGenerationUsageRestore, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO admin_generation_usage_restores (
                idempotency_key,
                user_id,
                restore_count,
                status
            )
            VALUES (
                :idempotencyKey,
                :userId,
                :restoreCount,
                'PROCESSING'
            )
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int claim(
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("userId") UUID userId,
            @Param("restoreCount") int restoreCount
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT restore
            FROM AdminGenerationUsageRestore restore
            WHERE restore.idempotencyKey = :idempotencyKey
            """)
    Optional<AdminGenerationUsageRestore> findByIdempotencyKeyForUpdate(
            @Param("idempotencyKey") UUID idempotencyKey
    );
}
