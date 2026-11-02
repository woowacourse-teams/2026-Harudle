package com.harudle.auth.infrastructure.oauth;

import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.auth.apple.enabled", havingValue = "true")
class AppleUnlinkStrategy implements SocialUnlinkStrategy {

    private final AppleTokenRevocationClient client;

    AppleUnlinkStrategy(AppleTokenRevocationClient client) {
        this.client = client;
    }

    @Override
    public boolean supports(OAuthProvider provider) {
        return provider == OAuthProvider.APPLE;
    }

    @Override
    public SocialUnlinkResult unlink(OAuthAccount account, SocialUnlinkCredentials credentials) {
        if (!(credentials instanceof SocialUnlinkCredentials.Apple apple)) {
            throw new IllegalArgumentException("애플 연결 해제 정보가 필요합니다.");
        }
        return switch (client.revoke(apple.clientId(), apple.token())) {
            case REVOKED -> SocialUnlinkResult.REVOKED;
            case TOKEN_UNAVAILABLE -> SocialUnlinkResult.APPLE_TOKEN_UNAVAILABLE;
        };
    }
}
