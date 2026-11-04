package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.infrastructure.oauth.AppleTokenRepository.EncryptedToken;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;

class AppleUnlinkStrategyTest {

    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    private final String key = Base64.getEncoder().encodeToString(new byte[32]);
    private final AppleTokenCipher cipher = new AppleTokenCipher(key);
    private final AppleTokenRepository tokens = mock(AppleTokenRepository.class);
    private final AppleTokenRevocationClient client = mock(AppleTokenRevocationClient.class);
    private final OAuth2AuthorizedClientService memory = mock(OAuth2AuthorizedClientService.class);
    private final OAuthAccount account = mock(OAuthAccount.class);

    @Test
    void revokesEachSavedTokenUsingItsOwnClientId() {
        when(account.getId()).thenReturn(1L);
        when(tokens.findByAccountId(1L)).thenReturn(List.of(
                new EncryptedToken("app", cipher.encrypt("app-refresh"), cipher.encrypt("app-access"), NOW),
                new EncryptedToken("web", null, cipher.encrypt("web-access"), NOW.plusSeconds(60))));
        when(client.revoke("app", "app-refresh")).thenReturn(AppleTokenRevocationClient.Result.REVOKED);
        when(client.revoke("web", "web-access")).thenReturn(AppleTokenRevocationClient.Result.REVOKED);

        assertThat(strategy(key).begin(account)).isEqualTo(SocialUnlinkResult.REVOKED);
        verify(client).revoke("app", "app-refresh");
        verify(client).revoke("web", "web-access");
        verifyNoInteractions(memory);
    }

    @Test
    void requestsManualGuidanceIfAnyClientHasNoUsableToken() {
        when(account.getId()).thenReturn(1L);
        when(tokens.findByAccountId(1L)).thenReturn(List.of(
                new EncryptedToken("app", cipher.encrypt("app-refresh"), cipher.encrypt("app-access"), NOW),
                new EncryptedToken("web", null, cipher.encrypt("expired"), NOW)));
        when(client.revoke("app", "app-refresh")).thenReturn(AppleTokenRevocationClient.Result.REVOKED);

        assertThat(strategy(key).begin(account)).isEqualTo(SocialUnlinkResult.APPLE_TOKEN_UNAVAILABLE);
        verify(client).revoke("app", "app-refresh");
        verifyNoInteractions(memory);
    }

    @Test
    void stopsWithdrawalWhenSavedTokensCannotBeDecrypted() {
        when(account.getId()).thenReturn(1L);
        when(tokens.findByAccountId(1L)).thenReturn(List.of(
                new EncryptedToken("app", cipher.encrypt("app-refresh"), cipher.encrypt("app-access"), NOW)));
        String wrongKey = Base64.getEncoder().encodeToString(new byte[] {
                1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});

        assertThatThrownBy(() -> strategy(wrongKey).begin(account)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(client, memory);
    }

    @Test
    void stopsWithdrawalWhenAppleRevocationFails() {
        when(account.getId()).thenReturn(1L);
        when(tokens.findByAccountId(1L)).thenReturn(List.of(
                new EncryptedToken("app", cipher.encrypt("app-refresh"), cipher.encrypt("app-access"), NOW)));
        when(client.revoke("app", "app-refresh")).thenThrow(new AppleTokenRevocationClient.AppleTokenRevocationException());

        assertThatThrownBy(() -> strategy(key).begin(account))
                .isInstanceOf(AppleTokenRevocationClient.AppleTokenRevocationException.class);
        verifyNoInteractions(memory);
    }

    @Test
    void requestsManualGuidanceWhenOnlyAccessTokenHasExpired() {
        when(account.getId()).thenReturn(1L);
        when(tokens.findByAccountId(1L)).thenReturn(List.of(
                new EncryptedToken("app", null, cipher.encrypt("expired"), NOW)));

        assertThat(strategy("").begin(account)).isEqualTo(SocialUnlinkResult.APPLE_TOKEN_UNAVAILABLE);
        verifyNoInteractions(client, memory);
    }

    private AppleUnlinkStrategy strategy(String encryptionKey) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("client", client);
        beans.addBean("store", new AppleTokenStore(tokens, encryptionKey));
        return new AppleUnlinkStrategy(beans.getBeanProvider(AppleTokenRevocationClient.class), memory, tokens,
                beans.getBeanProvider(AppleTokenStore.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
