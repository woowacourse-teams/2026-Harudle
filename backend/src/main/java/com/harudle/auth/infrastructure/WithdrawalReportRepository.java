package com.harudle.auth.infrastructure;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WithdrawalReportRepository {

    private final JdbcTemplate jdbc;

    public WithdrawalReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void anonymizeTarget(UUID userId) {
        jdbc.update("""
                UPDATE reports SET target_user_id = CASE WHEN target_user_id = ? THEN NULL ELSE target_user_id END,
                    target_id = CASE WHEN target_type = 'USER' AND target_id = ? THEN NULL ELSE target_id END
                WHERE target_user_id = ? OR (target_type = 'USER' AND target_id = ?)
                """, userId, userId, userId, userId);
    }
}
