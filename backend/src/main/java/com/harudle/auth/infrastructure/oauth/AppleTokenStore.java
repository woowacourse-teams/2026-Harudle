package com.harudle.auth.infrastructure.oauth;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@ConditionalOnProperty(name = "app.auth.apple.enabled", havingValue = "true")
public class AppleTokenStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(AppleTokenStore.class);
    private final AppleTokenRepository tokens;
    private final String encryptionKey;

    public AppleTokenStore(AppleTokenRepository tokens, @Value("${app.auth.apple.token-encryption-key:}") String encryptionKey) {
        this.tokens = tokens;
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
            tokens.save(userId, clientId, encryptedIdentity, encryptedAccess, encryptedRefresh, accessExpiresAt);
        } catch (RuntimeException exception) {
            LOGGER.warn("event=apple_token_save_failed exceptionType={}", exception.getClass().getSimpleName());
        }
    }
}
