package com.harudle.auth.infrastructure.oauth;

import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;

interface SocialUnlinkStrategy {

    boolean supports(OAuthProvider provider);

    SocialUnlinkResult begin(OAuthAccount account);

    SocialUnlinkResult unlink(OAuthAccount account, SocialUnlinkCredentials credentials);
}
