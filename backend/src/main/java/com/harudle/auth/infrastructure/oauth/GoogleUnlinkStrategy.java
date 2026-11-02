package com.harudle.auth.infrastructure.oauth;

import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import org.springframework.stereotype.Component;

@Component
class GoogleUnlinkStrategy implements SocialUnlinkStrategy {

    private final GoogleWithdrawalUnlinkService service;

    GoogleUnlinkStrategy(GoogleWithdrawalUnlinkService service) {
        this.service = service;
    }

    @Override
    public boolean supports(OAuthProvider provider) {
        return provider == OAuthProvider.GOOGLE;
    }

    @Override
    public SocialUnlinkResult unlink(OAuthAccount account, SocialUnlinkCredentials credentials) {
        if (!(credentials instanceof SocialUnlinkCredentials.Google google)) {
            throw new IllegalArgumentException("구글 재인증 정보가 필요합니다.");
        }
        service.unlink(account.getUser().getId(), google.authentication(), google.client());
        return SocialUnlinkResult.REVOKED;
    }
}
