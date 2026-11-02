package com.harudle.auth.infrastructure.oauth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@ConditionalOnProperty(name = "app.auth.apple.enabled", havingValue = "true")
public class AppleTokenStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(AppleTokenStore.class);
    private final AppleTokenRepository tokens;
    private final @Nullable AppleTokenCipher cipher;

    public AppleTokenStore(AppleTokenRepository tokens, @Value("${app.auth.apple.token-encryption-key:}") String encryptionKey) {
        this.tokens = tokens;
        this.cipher = createCipher(encryptionKey);
    }

    /** 토큰 저장이 실패해도 로그인은 계속한다. */
    public void save(UUID userId, String clientId, String identityToken, String accessToken,
            @Nullable String refreshToken, @Nullable Instant accessExpiresAt) {
        if (cipher == null) {
            logFailure("INVALID_KEY");
            return;
        }
        if (!StringUtils.hasText(clientId)) {
            logFailure("INVALID_CLIENT");
            return;
        }
        if (!StringUtils.hasText(identityToken) || !StringUtils.hasText(accessToken)) {
            logFailure("MISSING_TOKEN");
            return;
        }
        try {
            String encryptedIdentity = cipher.encrypt(identityToken);
            String encryptedAccess = cipher.encrypt(accessToken);
            String encryptedRefresh = StringUtils.hasText(refreshToken) ? cipher.encrypt(refreshToken) : null;
            tokens.save(userId, clientId, encryptedIdentity, encryptedAccess, encryptedRefresh, accessExpiresAt);
        } catch (DataAccessException exception) {
            logFailure("DATABASE_ERROR");
        } catch (RuntimeException exception) {
            logFailure("STORAGE_ERROR");
        }
    }

    public List<SocialUnlinkCredentials.Apple> decryptForUnlink(
            List<AppleTokenRepository.EncryptedToken> savedTokens, Instant now) {
        return savedTokens.stream().map(saved -> {
            String encrypted = saved.refreshToken();
            if (encrypted == null && (saved.accessExpiresAt() == null || saved.accessExpiresAt().isAfter(now))) {
                encrypted = saved.accessToken();
            }
            if (encrypted == null) {
                return new SocialUnlinkCredentials.Apple(saved.clientId(), null);
            }
            if (cipher == null) {
                throw new IllegalStateException("애플 토큰 암호화 키가 올바르지 않습니다.");
            }
            return new SocialUnlinkCredentials.Apple(saved.clientId(), cipher.decrypt(encrypted));
        }).toList();
    }

    private static @Nullable AppleTokenCipher createCipher(String encryptionKey) {
        try {
            return new AppleTokenCipher(encryptionKey);
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("event=apple_token_key_invalid reason=INVALID_KEY");
            return null;
        }
    }

    private static void logFailure(String reason) {
        LOGGER.warn("event=apple_token_save_failed reason={}", reason);
    }
}
