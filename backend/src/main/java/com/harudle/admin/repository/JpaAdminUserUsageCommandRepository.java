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
}
