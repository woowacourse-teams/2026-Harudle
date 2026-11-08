package com.harudle.auth.infrastructure;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WithdrawalModerationRepository {

    private static final String TARGETS = """
            WHERE (target_type = 'USER' AND target_id = ?)
               OR (target_type = 'FEED' AND target_id IN (
                    SELECT f.id FROM feeds f JOIN diaries d ON d.id = f.diary_id WHERE d.user_id = ?))
               OR (target_type = 'COMMENT' AND target_id IN (
                    SELECT c.id FROM comments c JOIN feeds f ON f.id = c.feed_id
                    JOIN diaries d ON d.id = f.diary_id WHERE d.user_id = ?))
            """;

    private final JdbcTemplate jdbc;

    public WithdrawalModerationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void deleteRestrictions(UUID userId) {
        // 관리자 제재도 일기부터 잠그므로, 삭제 대상이 조회된 뒤 새 제재가 생기지 않게 한다.
        jdbc.queryForList("SELECT id FROM diaries WHERE user_id = ? ORDER BY id FOR UPDATE", UUID.class, userId);
        jdbc.update("DELETE FROM moderation_events " + TARGETS, userId, userId, userId);
        jdbc.update("DELETE FROM moderation_states " + TARGETS, userId, userId, userId);
    }
}
