package com.harudle.auth.infrastructure.oauth;

import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import java.time.Clock;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.AbstractOAuth2Token;
import org.springframework.stereotype.Component;

@Component
class AppleUnlinkStrategy implements SocialUnlinkStrategy {

    private final ObjectProvider<AppleTokenRevocationClient> clients;
    private final OAuth2AuthorizedClientService authorizedClients;
    private final Clock clock;

    AppleUnlinkStrategy(ObjectProvider<AppleTokenRevocationClient> clients,
            OAuth2AuthorizedClientService authorizedClients, @Qualifier("authClock") Clock clock) {
        this.clients = clients;
        this.authorizedClients = authorizedClients;
        this.clock = clock;
    }

    @Override
    public boolean supports(OAuthProvider provider) {
        return provider == OAuthProvider.APPLE;
    }

    @Override
    public SocialUnlinkResult begin(OAuthAccount account) {
        var authorized = authorizedClients.loadAuthorizedClient("apple", account.getProviderSubject());
        if (authorized == null) {
            return SocialUnlinkResult.APPLE_TOKEN_UNAVAILABLE;
        }
        if (!account.getProviderSubject().equals(authorized.getPrincipalName())
                || !"apple".equals(authorized.getClientRegistration().getRegistrationId())) {
            throw new IllegalStateException("애플 인증 정보가 일치하지 않습니다.");
        }
        var refresh = authorized.getRefreshToken();
        var access = authorized.getAccessToken();
        String token = usable(refresh) ? refresh.getTokenValue() : usable(access) ? access.getTokenValue() : null;
        if (token == null) {
            return SocialUnlinkResult.APPLE_TOKEN_UNAVAILABLE;
        }
        return unlink(account, new SocialUnlinkCredentials.Apple(authorized.getClientRegistration().getClientId(), token));
    }

    private boolean usable(@Nullable AbstractOAuth2Token token) {
        return token != null && token.getTokenValue() != null && !token.getTokenValue().isBlank()
                && (token.getExpiresAt() == null || token.getExpiresAt().isAfter(clock.instant()));
    }

    @Override
    public SocialUnlinkResult unlink(OAuthAccount account, SocialUnlinkCredentials credentials) {
        if (!(credentials instanceof SocialUnlinkCredentials.Apple apple)) {
            throw new IllegalArgumentException("애플 연결 해제 정보가 필요합니다.");
        }
        if (apple.token() == null || apple.token().isBlank()) {
            return SocialUnlinkResult.APPLE_TOKEN_UNAVAILABLE;
        }
        var client = clients.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("애플 연결 해제 설정이 필요합니다.");
        }
        return switch (client.revoke(apple.clientId(), apple.token())) {
            case REVOKED -> SocialUnlinkResult.REVOKED;
            case TOKEN_UNAVAILABLE -> SocialUnlinkResult.APPLE_TOKEN_UNAVAILABLE;
        };
    }
}
