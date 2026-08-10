package com.harudle.generation.infrastructure;

import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.repository.GenerationUsageRepository;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGenerationUsageRepository implements GenerationUsageRepository {

    private static final String FIND_QUERY = """
            SELECT usage_date, used_count, limit_count
            FROM daily_generation_usage
            WHERE user_id = ?
              AND usage_date = ?
            """;

    private static final String INCREMENT_QUERY = """
            INSERT INTO daily_generation_usage (
                user_id,
                usage_date,
                used_count,
                limit_count,
                created_at,
                updated_at
            )
            VALUES (?, ?, 1, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (user_id, usage_date)
            DO UPDATE
               SET used_count = daily_generation_usage.used_count + 1,
                   updated_at = CURRENT_TIMESTAMP
             WHERE daily_generation_usage.used_count < daily_generation_usage.limit_count
            RETURNING usage_date, used_count, limit_count
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcGenerationUsageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<GenerationUsage> find(UUID userId, LocalDate usageDate) {
        validateParameters(userId, usageDate);
        List<GenerationUsage> usages = jdbcTemplate.query(
                FIND_QUERY,
                (resultSet, rowNumber) -> mapUsage(resultSet.getDate("usage_date"),
                        resultSet.getInt("used_count"), resultSet.getInt("limit_count")),
                userId,
                Date.valueOf(usageDate)
        );
        return usages.stream().findFirst();
    }

    @Override
    public Optional<GenerationUsage> incrementWithinLimit(UUID userId, LocalDate usageDate) {
        validateParameters(userId, usageDate);
        List<GenerationUsage> usages = jdbcTemplate.query(
                INCREMENT_QUERY,
                (resultSet, rowNumber) -> mapUsage(resultSet.getDate("usage_date"),
                        resultSet.getInt("used_count"), resultSet.getInt("limit_count")),
                userId,
                Date.valueOf(usageDate),
                GenerationUsage.DEFAULT_LIMIT_COUNT
        );
        return usages.stream().findFirst();
    }

    private static GenerationUsage mapUsage(Date usageDate, int usedCount, int limitCount) {
        return new GenerationUsage(usageDate.toLocalDate(), usedCount, limitCount);
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
