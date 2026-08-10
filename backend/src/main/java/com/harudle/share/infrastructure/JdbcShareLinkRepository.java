package com.harudle.share.infrastructure;

import com.harudle.share.repository.ShareLinkRepository;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcShareLinkRepository implements ShareLinkRepository {

    private static final String DELETE_BY_DIARY_ID_QUERY = """
            DELETE FROM share_links
            WHERE generation_id = (
                SELECT id
                FROM comic_generations
                WHERE diary_id = ?
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcShareLinkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int deleteByDiaryId(UUID diaryId) {
        if (diaryId == null) {
            throw new IllegalArgumentException("일기 ID는 필수입니다.");
        }
        return jdbcTemplate.update(DELETE_BY_DIARY_ID_QUERY, diaryId);
    }
}
