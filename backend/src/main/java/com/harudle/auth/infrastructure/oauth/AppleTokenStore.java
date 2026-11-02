package com.harudle.auth.infrastructure.oauth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@ConditionalOnProperty(name = "app.auth.apple.enabled", havingValue = "true")
public class AppleTokenStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(AppleTokenStore.class);
    private final JdbcTemplate jdbc;
    private final String encryptionKey;

    public AppleTokenStore(JdbcTemplate jdbc, @Value("${app.auth.apple.token-encryption-key:}") String encryptionKey) {
        this.jdbc = jdbc;
        this.encryptionKey = encryptionKey;
    }

    /** 토큰 저장이 실패해도 로그인은 계속한다. */
    public void save(UUID userId, String clientId, String identityToken, String accessToken,
            @Nullable String refreshToken, @Nullable Instant accessExpiresAt) {
        try {
            if (!StringUtils.hasText(clientId)) {
                throw new IllegalArgumentException("애플 클라이언트 ID가 필요합니다.");
            }
            var cipher = new AppleTokenCipher(encryptionKey);
            String encryptedIdentity = cipher.encrypt(identityToken);
            String encryptedAccess = cipher.encrypt(accessToken);
            String encryptedRefresh = StringUtils.hasText(refreshToken) ? cipher.encrypt(refreshToken) : null;
            int updated = jdbc.update("""
                    INSERT INTO apple_oauth_tokens (oauth_account_id, client_id, identity_token, access_token,
                        refresh_token, access_expires_at)
                    SELECT id, ?, ?, ?, ?, ? FROM oauth_accounts WHERE user_id = ? AND provider = 'APPLE'
                    ON CONFLICT (oauth_account_id, client_id) DO UPDATE SET
                        identity_token = EXCLUDED.identity_token, access_token = EXCLUDED.access_token,
                        refresh_token = COALESCE(EXCLUDED.refresh_token, apple_oauth_tokens.refresh_token),
                        access_expires_at = EXCLUDED.access_expires_at, updated_at = CURRENT_TIMESTAMP
                    """, clientId, encryptedIdentity, encryptedAccess, encryptedRefresh,
                    accessExpiresAt == null ? null : Timestamp.from(accessExpiresAt), userId);
            if (updated != 1) {
                throw new IllegalStateException("애플 로그인 계정을 찾을 수 없습니다.");
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("event=apple_token_save_failed exceptionType={}", exception.getClass().getSimpleName());
        }
    }
}
