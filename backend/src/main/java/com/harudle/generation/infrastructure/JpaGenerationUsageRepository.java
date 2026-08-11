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
public class JpaGenerationUsageRepository implements GenerationUsageRepository {

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
                VALUES (?1, ?2, 1, ?3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (user_id, usage_date)
                DO UPDATE
                   SET used_count = daily_generation_usage.used_count + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE daily_generation_usage.used_count < daily_generation_usage.limit_count
                RETURNING usage_date, used_count, limit_count
            )
            SELECT usage_date, used_count, limit_count
            FROM incremented_usage
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

    public JpaGenerationUsageRepository(EntityManager entityManager) {
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
        List<Object[]> usages = entityManager
                .createNativeQuery(INCREMENT_QUERY)
                .setParameter(1, userId)
                .setParameter(2, usageDate)
                .setParameter(3, GenerationUsage.DEFAULT_LIMIT_COUNT)
                .getResultList();
        return usages.stream()
                .map(JpaGenerationUsageRepository::mapUsage)
                .findFirst();
    }

    private static GenerationUsage mapUsage(Object[] columns) {
        return new GenerationUsage(
                toLocalDate(columns[0]),
                ((Number) columns[1]).intValue(),
                ((Number) columns[2]).intValue()
        );
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        throw new IllegalStateException("생성 사용일 조회 결과 형식을 처리할 수 없습니다.");
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
