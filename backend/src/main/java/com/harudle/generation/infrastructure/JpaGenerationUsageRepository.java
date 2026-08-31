package com.harudle.generation.infrastructure;

import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.repository.GenerationUsageRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaGenerationUsageRepository implements GenerationUsageRepository {

    private static final int USAGE_INCREMENT = 1;
    private static final String LOCK_USER_QUERY = """
            SELECT id
            FROM users
            WHERE id = :userId
            FOR UPDATE
            """;
    private static final String INCREMENT_QUERY = """
            WITH incremented_usage AS (
                INSERT INTO daily_generation_usage (
                    user_id,
                    usage_date,
                    used_count,
                    limit_count,
                    created_at,
                    updated_at
                )
                SELECT
                    :userId,
                    :usageDate,
                    :usageIncrement,
                    u.daily_generation_limit,
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP
                FROM users u
                WHERE u.id = :userId
                  AND u.daily_generation_limit >= :usageIncrement
                ON CONFLICT (user_id, usage_date)
                DO UPDATE
                   SET used_count = daily_generation_usage.used_count + EXCLUDED.used_count,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE daily_generation_usage.used_count < daily_generation_usage.limit_count
                RETURNING used_count, limit_count
            )
            SELECT used_count, limit_count
            FROM incremented_usage
            """;

    private static final String UPDATE_LIMIT_COUNT_QUERY = """
            UPDATE daily_generation_usage
               SET limit_count = :limitCount,
                   updated_at = CURRENT_TIMESTAMP
             WHERE user_id = :userId
               AND usage_date = :usageDate
               AND used_count <= :limitCount
            """;

    private static final String RESTORE_QUERY = """
            UPDATE daily_generation_usage
               SET used_count = used_count - :restoreCount,
                   updated_at = CURRENT_TIMESTAMP
             WHERE user_id = :userId
               AND usage_date = :usageDate
               AND used_count >= :restoreCount
            RETURNING used_count, limit_count
            """;

    private static final String RESET_QUERY = """
            UPDATE daily_generation_usage
               SET used_count = 0,
                   limit_count = :limitCount,
                   updated_at = CURRENT_TIMESTAMP
             WHERE user_id = :userId
               AND usage_date = :usageDate
            RETURNING used_count, limit_count
            """;

    private static final String FIND_QUERY = """
            SELECT new com.harudle.generation.domain.GenerationUsage(
                usage.id.usageDate,
                usage.usedCount,
                usage.limitCount
            )
            FROM DailyGenerationUsage usage
            WHERE usage.id.userId = :userId
              AND usage.id.usageDate = :usageDate
            """;

    private final EntityManager entityManager;

    JpaGenerationUsageRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GenerationUsage> find(UUID userId, LocalDate usageDate) {
        validateParameters(userId, usageDate);
        return entityManager.createQuery(FIND_QUERY, GenerationUsage.class)
                .setParameter("userId", userId)
                .setParameter("usageDate", usageDate)
                .getResultStream()
                .findFirst();
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public Optional<GenerationUsage> tryIncrementWithinLimit(UUID userId, LocalDate usageDate) {
        validateParameters(userId, usageDate);
        lockUserRow(userId);
        List<Object[]> usages = entityManager
                .createNativeQuery(INCREMENT_QUERY)
                .setParameter("userId", userId)
                .setParameter("usageDate", usageDate)
                .setParameter("usageIncrement", USAGE_INCREMENT)
                .getResultList();
        return usages.stream()
                .map(columns -> mapUsage(usageDate, columns))
                .findFirst();
    }

    @Override
    @Transactional
    public int updateLimitCount(UUID userId, LocalDate usageDate, int limitCount) {
        validateParameters(userId, usageDate);
        if (limitCount < 1) {
            throw new IllegalArgumentException("일일 생성 한도는 1 이상이어야 합니다.");
        }
        lockUserRow(userId);
        return entityManager
                .createNativeQuery(UPDATE_LIMIT_COUNT_QUERY)
                .setParameter("userId", userId)
                .setParameter("usageDate", usageDate)
                .setParameter("limitCount", limitCount)
                .executeUpdate();
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public Optional<GenerationUsage> tryRestore(UUID userId, LocalDate usageDate, int restoreCount) {
        validateParameters(userId, usageDate);
        if (restoreCount < 1) {
            throw new IllegalArgumentException("복구 횟수는 1 이상이어야 합니다.");
        }
        List<Object[]> usages = entityManager
                .createNativeQuery(RESTORE_QUERY)
                .setParameter("userId", userId)
                .setParameter("usageDate", usageDate)
                .setParameter("restoreCount", restoreCount)
                .getResultList();
        return usages.stream()
                .map(columns -> mapUsage(usageDate, columns))
                .findFirst();
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public Optional<GenerationUsage> tryReset(UUID userId, LocalDate usageDate, int limitCount) {
        validateParameters(userId, usageDate);
        if (limitCount < 1) {
            throw new IllegalArgumentException("일일 생성 한도는 1 이상이어야 합니다.");
        }
        List<Object[]> usages = entityManager
                .createNativeQuery(RESET_QUERY)
                .setParameter("userId", userId)
                .setParameter("usageDate", usageDate)
                .setParameter("limitCount", limitCount)
                .getResultList();
        return usages.stream()
                .map(columns -> mapUsage(usageDate, columns))
                .findFirst();
    }

    private static GenerationUsage mapUsage(LocalDate usageDate, Object[] columns) {
        return new GenerationUsage(
                usageDate,
                ((Number) columns[0]).intValue(),
                ((Number) columns[1]).intValue()
        );
    }

    private void lockUserRow(UUID userId) {
        entityManager.createNativeQuery(LOCK_USER_QUERY)
                .setParameter("userId", userId)
                .getResultList();
    }

    private static void validateParameters(UUID userId, LocalDate usageDate) {
        if (userId == null) {
            throw new IllegalArgumentException("사용자 ID는 필수입니다.");
        }
        if (usageDate == null) {
            throw new IllegalArgumentException("생성 사용일은 필수입니다.");
        }
    }
}
