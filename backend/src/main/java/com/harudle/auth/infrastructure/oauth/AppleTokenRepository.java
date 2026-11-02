package com.harudle.auth.infrastructure.oauth;

import com.harudle.common.persistence.PostgresTransactionLockTimeout;
import java.sql.Timestamp;
import java.time.Instant;
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
}
