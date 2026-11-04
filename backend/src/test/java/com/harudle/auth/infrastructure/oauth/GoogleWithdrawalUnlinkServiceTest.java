package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.oauth.GoogleWithdrawalUnlinkService.GoogleWithdrawalAuthenticationException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class GoogleWithdrawalUnlinkServiceTest {

    @Test
    @DisplayName("같은 구글 계정으로 재인증한 경우에만 토큰 연결을 해제한다")
    void unlinkSameGoogleAccount() {
        var fixture = new Fixture();

        fixture.service.unlink(fixture.userId, fixture.authentication, fixture.client);

        verify(fixture.revocationClient).revoke("google-token");
    }

    @Test
    @DisplayName("다른 구글 계정으로 재인증하면 연결을 해제하지 않는다")
    void rejectDifferentGoogleAccount() {
        var fixture = new Fixture();
        when(fixture.googleUser.getSubject()).thenReturn("another-user");

        assertThatThrownBy(() -> fixture.service.unlink(fixture.userId, fixture.authentication, fixture.client))
                .isInstanceOf(GoogleWithdrawalAuthenticationException.class);
        verifyNoInteractions(fixture.revocationClient);
    }

    @Test
    @DisplayName("재인증한 사용자와 토큰의 소유자가 다르면 연결을 해제하지 않는다")
    void rejectTokenForDifferentPrincipal() {
        var fixture = new Fixture();
        when(fixture.client.getPrincipalName()).thenReturn("another-user");

        assertThatThrownBy(() -> fixture.service.unlink(fixture.userId, fixture.authentication, fixture.client))
                .isInstanceOf(GoogleWithdrawalAuthenticationException.class);
        verifyNoInteractions(fixture.revocationClient);
    }

    private static class Fixture {
        private final UUID userId = UUID.randomUUID();
        private final OAuthAccountRepository accounts = mock(OAuthAccountRepository.class);
        private final GoogleTokenRevocationClient revocationClient = mock(GoogleTokenRevocationClient.class);
        private final GoogleWithdrawalUnlinkService service = new GoogleWithdrawalUnlinkService(accounts, revocationClient);
        private final OAuth2AuthenticationToken authentication = mock(OAuth2AuthenticationToken.class);
        private final OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
        private final OidcUser googleUser = mock(OidcUser.class);

        private Fixture() {
            var account = mock(OAuthAccount.class);
            var registration = mock(ClientRegistration.class);
            var token = mock(OAuth2AccessToken.class);
            when(accounts.findByUser_Id(userId)).thenReturn(Optional.of(account));
            when(account.getProvider()).thenReturn(OAuthProvider.GOOGLE);
            when(account.getProviderSubject()).thenReturn("google-user");
            when(authentication.isAuthenticated()).thenReturn(true);
            when(authentication.getAuthorizedClientRegistrationId()).thenReturn("google");
            when(authentication.getPrincipal()).thenReturn(googleUser);
            when(authentication.getName()).thenReturn("google-user");
            when(googleUser.getSubject()).thenReturn("google-user");
            when(client.getClientRegistration()).thenReturn(registration);
            when(registration.getRegistrationId()).thenReturn("google");
            when(client.getPrincipalName()).thenReturn("google-user");
            when(client.getAccessToken()).thenReturn(token);
            when(token.getTokenValue()).thenReturn("google-token");
        }
    }
}
