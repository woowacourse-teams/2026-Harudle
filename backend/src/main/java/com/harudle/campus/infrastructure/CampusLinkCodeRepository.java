package com.harudle.campus.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CampusLinkCodeRepository {

    private static final String INSERT = """
            INSERT INTO campus_link_codes (user_id, code_hash, state_hash, expires_at, created_at)
            VALUES (?, ?, ?, ?, ?)
            """;
    // 한 문장으로 사용 처리해 같은 코드를 동시에 교환해도 하나만 성공한다.
    private static final String CONSUME = """
            UPDATE campus_link_codes
            SET used_at = ?
            WHERE code_hash = ? AND used_at IS NULL AND expires_at > ?
            RETURNING user_id, state_hash
            """;
    private static final String DELETE_EXPIRED = "DELETE FROM campus_link_codes WHERE expires_at <= ?";

    private final JdbcTemplate jdbcTemplate;

    public CampusLinkCodeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(UUID userId, String codeHash, String stateHash, Instant expiresAt, Instant createdAt) {
        jdbcTemplate.update(INSERT, userId, codeHash, stateHash, Timestamp.from(expiresAt), Timestamp.from(createdAt));
    }

    public Optional<ConsumedCampusLinkCode> consume(String codeHash, Instant now) {
        Timestamp at = Timestamp.from(now);
        List<ConsumedCampusLinkCode> rows = jdbcTemplate.query(
                CONSUME,
                (resultSet, rowNumber) -> new ConsumedCampusLinkCode(
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getString("state_hash")
                ),
                at, codeHash, at
        );
        return rows.stream().findFirst();
    }

    public int deleteExpired(Instant cutoff) {
        return jdbcTemplate.update(DELETE_EXPIRED, Timestamp.from(cutoff));
    }

    public record ConsumedCampusLinkCode(UUID userId, String stateHash) {
    }
}
