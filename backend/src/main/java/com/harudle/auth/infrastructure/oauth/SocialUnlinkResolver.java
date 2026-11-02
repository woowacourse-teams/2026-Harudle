package com.harudle.auth.infrastructure.oauth;

import com.harudle.auth.domain.OAuthAccount;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SocialUnlinkResolver {

    private final List<SocialUnlinkStrategy> strategies;

    SocialUnlinkResolver(List<SocialUnlinkStrategy> strategies) {
        this.strategies = List.copyOf(strategies);
    }

    public SocialUnlinkResult unlink(OAuthAccount account, SocialUnlinkCredentials credentials) {
        var supported = strategies.stream().filter(strategy -> strategy.supports(account.getProvider())).toList();
        if (supported.size() != 1) {
            throw new IllegalStateException("소셜 연결 해제를 처리할 객체가 없거나 중복되었습니다.");
        }
        return supported.getFirst().unlink(account, credentials);
    }
}
