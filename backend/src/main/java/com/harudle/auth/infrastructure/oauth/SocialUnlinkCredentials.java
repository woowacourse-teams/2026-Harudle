package com.harudle.auth.infrastructure.oauth;

import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;

public sealed interface SocialUnlinkCredentials {

    record Kakao() implements SocialUnlinkCredentials {}

    record Google(OAuth2AuthenticationToken authentication, OAuth2AuthorizedClient client)
            implements SocialUnlinkCredentials {}

    /** 애플 토큰과 clientId는 해당 회원에게 발급된 값이어야 한다. */
    record Apple(String clientId, @Nullable String token) implements SocialUnlinkCredentials {
        @Override
        public String toString() {
            return "Apple[credentials=redacted]";
        }
    }
}
