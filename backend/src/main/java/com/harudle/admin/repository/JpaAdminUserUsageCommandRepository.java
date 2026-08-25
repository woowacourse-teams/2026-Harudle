package com.harudle.admin.repository;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaAdminUserUsageCommandRepository implements AdminUserUsageCommandRepository {
    private final EntityManager entityManager;

    JpaAdminUserUsageCommandRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void changeDailyGenerationLimit(UUID userId, int limitCount, LocalDate usageDate) {
        int updated = entityManager.createQuery("""
                UPDATE User u SET u.dailyGenerationLimit = :limitCount WHERE u.id = :userId
                """).setParameter("limitCount", limitCount).setParameter("userId", userId).executeUpdate();
        if (updated == 0) throw new AdminUserNotFoundException(userId);
        entityManager.createQuery("""
                UPDATE DailyGenerationUsage usage SET usage.limitCount = :limitCount
                WHERE usage.id.userId = :userId AND usage.id.usageDate = :usageDate
                AND usage.usedCount <= :limitCount
                """).setParameter("limitCount", limitCount).setParameter("userId", userId)
                .setParameter("usageDate", usageDate).executeUpdate();
    }

    @Override
    @Transactional
    public void changeUsedCount(UUID userId, int usedCount, LocalDate usageDate) {
        int updated = entityManager.createNativeQuery("""
                UPDATE daily_generation_usage
                   SET used_count = :usedCount, updated_at = CURRENT_TIMESTAMP
                 WHERE user_id = :userId AND usage_date = :usageDate
                   AND :usedCount <= limit_count
                """).setParameter("usedCount", usedCount).setParameter("userId", userId)
                .setParameter("usageDate", usageDate).executeUpdate();
        if (updated > 0) return;
        Number existingLimit = (Number) entityManager.createNativeQuery("""
                SELECT COALESCE(
                    (SELECT limit_count FROM daily_generation_usage WHERE user_id = :userId AND usage_date = :usageDate),
                    (SELECT daily_generation_limit FROM users WHERE id = :userId))
                """).setParameter("userId", userId).setParameter("usageDate", usageDate).getSingleResult();
        if (existingLimit == null) throw new AdminUserNotFoundException(userId);
        if (usedCount > existingLimit.intValue()) throw new AdminUsageCountOutOfRangeException();
        entityManager.createNativeQuery("""
                INSERT INTO daily_generation_usage(user_id, usage_date, used_count, limit_count)
                VALUES (:userId, :usageDate, :usedCount,
                        (SELECT daily_generation_limit FROM users WHERE id = :userId))
                ON CONFLICT (user_id, usage_date) DO UPDATE
                   SET used_count = :usedCount, updated_at = CURRENT_TIMESTAMP
                """).setParameter("userId", userId).setParameter("usageDate", usageDate)
                .setParameter("usedCount", usedCount).executeUpdate();
    }

    @Override
    @Transactional
    public void restoreUsedCount(UUID userId, int count, LocalDate usageDate) {
        int updated = entityManager.createNativeQuery("""
                UPDATE daily_generation_usage
                   SET used_count = used_count - :count, updated_at = CURRENT_TIMESTAMP
                 WHERE user_id = :userId AND usage_date = :usageDate
                   AND used_count >= :count
                """).setParameter("count", count).setParameter("userId", userId)
                .setParameter("usageDate", usageDate).executeUpdate();
        if (updated > 0) return;
        Number exists = (Number) entityManager.createNativeQuery("""
                SELECT COUNT(*) FROM users WHERE id = :userId AND deleted_at IS NULL
                """).setParameter("userId", userId).getSingleResult();
        if (exists.intValue() == 0) throw new AdminUserNotFoundException(userId);
        throw new AdminUsageCountOutOfRangeException();
    }
}
