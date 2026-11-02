package com.harudle.auth.infrastructure.oauth;

import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import org.springframework.stereotype.Component;

@Component
class KakaoUnlinkStrategy implements SocialUnlinkStrategy {

    private final KakaoUnlinkClient client;

    KakaoUnlinkStrategy(KakaoUnlinkClient client) {
        this.client = client;
    }

    @Override
    public boolean supports(OAuthProvider provider) {
        return provider == OAuthProvider.KAKAO;
    }

    @Override
    public SocialUnlinkResult begin(OAuthAccount account) {
        return unlink(account, new SocialUnlinkCredentials.Kakao());
    }

    @Override
    public SocialUnlinkResult unlink(OAuthAccount account, SocialUnlinkCredentials credentials) {
        if (!(credentials instanceof SocialUnlinkCredentials.Kakao)) {
            throw new IllegalArgumentException("카카오 연결 해제 정보가 필요합니다.");
        }
        client.unlink(Long.parseLong(account.getProviderSubject()));
        return SocialUnlinkResult.REVOKED;
    }
}
