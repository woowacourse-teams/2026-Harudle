package com.harudle.auth.infrastructure.oauth;

import com.harudle.common.persistence.PostgresTransactionLockTimeout;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AppleTokenRepository {

    private final JdbcTemplate jdbc;
    private final PostgresTransactionLockTimeout lockTimeout;

    public AppleTokenRepository(JdbcTemplate jdbc, PostgresTransactionLockTimeout lockTimeout) {
        this.jdbc = jdbc;
        this.lockTimeout = lockTimeout;
    }

    public List<EncryptedToken> findByAccountId(long accountId) {
        return jdbc.query("""
                SELECT client_id, refresh_token, access_token, access_expires_at
                FROM apple_oauth_tokens WHERE oauth_account_id = ? ORDER BY client_id
                """, (row, index) -> {
                    Timestamp expiresAt = row.getTimestamp("access_expires_at");
                    return new EncryptedToken(row.getString("client_id"), row.getString("refresh_token"),
                            row.getString("access_token"), expiresAt == null ? null : expiresAt.toInstant());
                }, accountId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(UUID userId, String clientId, String identityToken, String accessToken,
            @Nullable String refreshToken, @Nullable Instant accessExpiresAt) {
        lockTimeout.apply();
        int updated = jdbc.update("""
                INSERT INTO apple_oauth_tokens (oauth_account_id, client_id, identity_token, access_token,
                    refresh_token, access_expires_at)
                SELECT id, ?, ?, ?, ?, ? FROM oauth_accounts WHERE user_id = ? AND provider = 'APPLE'
                ON CONFLICT (oauth_account_id, client_id) DO UPDATE SET
                    identity_token = EXCLUDED.identity_token, access_token = EXCLUDED.access_token,
                    refresh_token = COALESCE(EXCLUDED.refresh_token, apple_oauth_tokens.refresh_token),
                    access_expires_at = EXCLUDED.access_expires_at, updated_at = CURRENT_TIMESTAMP
                """, clientId, identityToken, accessToken, refreshToken,
                accessExpiresAt == null ? null : Timestamp.from(accessExpiresAt), userId);
        if (updated != 1) {
            throw new IllegalStateException("애플 로그인 계정을 찾을 수 없습니다.");
        }
    }

    public record EncryptedToken(String clientId, @Nullable String refreshToken, String accessToken,
                                 @Nullable Instant accessExpiresAt) {
        @Override
        public String toString() {
            return "EncryptedToken[credentials=redacted]";
        }
    }
}
