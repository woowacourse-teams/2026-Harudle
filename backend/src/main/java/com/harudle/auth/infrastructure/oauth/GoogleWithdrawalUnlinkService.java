package com.harudle.auth.infrastructure.oauth;

import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import java.util.UUID;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
public class GoogleWithdrawalUnlinkService {

    private final OAuthAccountRepository accounts;
    private final GoogleTokenRevocationClient revocationClient;

    public GoogleWithdrawalUnlinkService(
            OAuthAccountRepository accounts,
            GoogleTokenRevocationClient revocationClient
    ) {
        this.accounts = accounts;
        this.revocationClient = revocationClient;
    }

    /** userId는 인증된 탈퇴 요청에 연결된 값이어야 한다. */
    public void unlink(UUID userId, OAuth2AuthenticationToken authentication, OAuth2AuthorizedClient client) {
        OAuthAccount account = accounts.findByUser_Id(userId)
                .orElseThrow(GoogleWithdrawalAuthenticationException::new);
        if (account.getProvider() != OAuthProvider.GOOGLE
                || !authentication.isAuthenticated()
                || !"google".equals(authentication.getAuthorizedClientRegistrationId())
                || !(authentication.getPrincipal() instanceof OidcUser googleUser)
                || !account.getProviderSubject().equals(googleUser.getSubject())
                || !"google".equals(client.getClientRegistration().getRegistrationId())
                || !authentication.getName().equals(client.getPrincipalName())) {
            throw new GoogleWithdrawalAuthenticationException();
        }
        revocationClient.revoke(client.getAccessToken().getTokenValue());
    }

    public static final class GoogleWithdrawalAuthenticationException extends RuntimeException {}
}
