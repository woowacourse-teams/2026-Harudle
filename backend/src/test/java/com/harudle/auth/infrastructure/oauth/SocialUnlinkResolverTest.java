package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import java.util.List;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

class SocialUnlinkResolverTest {

    @Test
    void selectsOnlyTheMatchingProvider() {
        var kakao = mock(KakaoUnlinkClient.class);
        var google = mock(GoogleWithdrawalUnlinkService.class);
        var account = mock(OAuthAccount.class);
        when(account.getProvider()).thenReturn(OAuthProvider.KAKAO);
        when(account.getProviderSubject()).thenReturn("123");
        var resolver = new SocialUnlinkResolver(List.of(new KakaoUnlinkStrategy(kakao), new GoogleUnlinkStrategy(google)));

        assertThat(resolver.unlink(account, new SocialUnlinkCredentials.Kakao())).isEqualTo(SocialUnlinkResult.REVOKED);
        verify(kakao).unlink(123);
        verifyNoInteractions(google);
    }

    @Test
    void refusesMissingOrDuplicateStrategies() {
        var account = mock(OAuthAccount.class);
        when(account.getProvider()).thenReturn(OAuthProvider.KAKAO);
        var strategy = new KakaoUnlinkStrategy(mock(KakaoUnlinkClient.class));

        assertThatThrownBy(() -> new SocialUnlinkResolver(List.of()).unlink(account, new SocialUnlinkCredentials.Kakao()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SocialUnlinkResolver(List.of(strategy, strategy))
                .unlink(account, new SocialUnlinkCredentials.Kakao())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void preservesAppleTokenUnavailableResult() {
        var client = mock(AppleTokenRevocationClient.class);
        var account = mock(OAuthAccount.class);
        when(account.getProvider()).thenReturn(OAuthProvider.APPLE);
        var beans = new StaticListableBeanFactory();
        beans.addBean("appleClient", client);
        var resolver = new SocialUnlinkResolver(List.of(new AppleUnlinkStrategy(
                beans.getBeanProvider(AppleTokenRevocationClient.class), mock(OAuth2AuthorizedClientService.class),
                mock(AppleTokenRepository.class), beans.getBeanProvider(AppleTokenStore.class), Clock.systemUTC())));

        assertThat(resolver.unlink(account, new SocialUnlinkCredentials.Apple("apple-client", null)))
                .isEqualTo(SocialUnlinkResult.APPLE_TOKEN_UNAVAILABLE);
    }

    @Test
    void appleUsesTheSavedMembersTokenWhenAvailable() {
        var client = mock(AppleTokenRevocationClient.class);
        var clients = mock(OAuth2AuthorizedClientService.class);
        var authorized = mock(OAuth2AuthorizedClient.class);
        var registration = mock(ClientRegistration.class);
        var token = mock(OAuth2RefreshToken.class);
        var account = mock(OAuthAccount.class);
        when(account.getProvider()).thenReturn(OAuthProvider.APPLE);
        when(account.getProviderSubject()).thenReturn("apple-user");
        when(account.getId()).thenReturn(1L);
        when(clients.loadAuthorizedClient("apple", "apple-user")).thenReturn(authorized);
        when(authorized.getPrincipalName()).thenReturn("apple-user");
        when(authorized.getClientRegistration()).thenReturn(registration);
        when(registration.getRegistrationId()).thenReturn("apple");
        when(registration.getClientId()).thenReturn("apple-client");
        when(authorized.getRefreshToken()).thenReturn(token);
        when(token.getTokenValue()).thenReturn("apple-token");
        when(client.revoke("apple-client", "apple-token")).thenReturn(AppleTokenRevocationClient.Result.REVOKED);
        var beans = new StaticListableBeanFactory();
        beans.addBean("appleClient", client);
        var resolver = new SocialUnlinkResolver(List.of(new AppleUnlinkStrategy(
                beans.getBeanProvider(AppleTokenRevocationClient.class), clients,
                mock(AppleTokenRepository.class), beans.getBeanProvider(AppleTokenStore.class), Clock.systemUTC())));

        assertThat(resolver.begin(account)).isEqualTo(SocialUnlinkResult.REVOKED);
        verify(client).revoke("apple-client", "apple-token");
    }

    @Test
    void appleWithoutSavedTokenRequestsManualGuidance() {
        var clients = mock(OAuth2AuthorizedClientService.class);
        var account = mock(OAuthAccount.class);
        when(account.getProvider()).thenReturn(OAuthProvider.APPLE);
        when(account.getProviderSubject()).thenReturn("apple-user");
        when(account.getId()).thenReturn(1L);
        var beans = new StaticListableBeanFactory();
        var resolver = new SocialUnlinkResolver(List.of(new AppleUnlinkStrategy(
                beans.getBeanProvider(AppleTokenRevocationClient.class), clients,
                mock(AppleTokenRepository.class), beans.getBeanProvider(AppleTokenStore.class), Clock.systemUTC())));

        assertThat(resolver.begin(account)).isEqualTo(SocialUnlinkResult.APPLE_TOKEN_UNAVAILABLE);
    }

    @Test
    void appleDoesNotRevokeTokenForAnotherMember() {
        var client = mock(AppleTokenRevocationClient.class);
        var clients = mock(OAuth2AuthorizedClientService.class);
        var authorized = mock(OAuth2AuthorizedClient.class);
        var account = mock(OAuthAccount.class);
        when(account.getProvider()).thenReturn(OAuthProvider.APPLE);
        when(account.getProviderSubject()).thenReturn("apple-user");
        when(account.getId()).thenReturn(1L);
        when(clients.loadAuthorizedClient("apple", "apple-user")).thenReturn(authorized);
        when(authorized.getPrincipalName()).thenReturn("different-user");
        var beans = new StaticListableBeanFactory();
        beans.addBean("appleClient", client);
        var resolver = new SocialUnlinkResolver(List.of(new AppleUnlinkStrategy(
                beans.getBeanProvider(AppleTokenRevocationClient.class), clients,
                mock(AppleTokenRepository.class), beans.getBeanProvider(AppleTokenStore.class), Clock.systemUTC())));

        assertThatThrownBy(() -> resolver.begin(account)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(client);
    }

    @Test
    void expiredAppleTokenIsTreatedAsUnavailable() {
        var client = mock(AppleTokenRevocationClient.class);
        var clients = mock(OAuth2AuthorizedClientService.class);
        var authorized = mock(OAuth2AuthorizedClient.class);
        var registration = mock(ClientRegistration.class);
        var token = mock(OAuth2RefreshToken.class);
        var account = mock(OAuthAccount.class);
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        when(account.getProvider()).thenReturn(OAuthProvider.APPLE);
        when(account.getProviderSubject()).thenReturn("apple-user");
        when(account.getId()).thenReturn(1L);
        when(clients.loadAuthorizedClient("apple", "apple-user")).thenReturn(authorized);
        when(authorized.getPrincipalName()).thenReturn("apple-user");
        when(authorized.getClientRegistration()).thenReturn(registration);
        when(registration.getRegistrationId()).thenReturn("apple");
        when(authorized.getRefreshToken()).thenReturn(token);
        when(token.getTokenValue()).thenReturn("expired-token");
        when(token.getExpiresAt()).thenReturn(now.minusSeconds(1));
        var beans = new StaticListableBeanFactory();
        beans.addBean("appleClient", client);
        var resolver = new SocialUnlinkResolver(List.of(new AppleUnlinkStrategy(
                beans.getBeanProvider(AppleTokenRevocationClient.class), clients,
                mock(AppleTokenRepository.class), beans.getBeanProvider(AppleTokenStore.class), Clock.fixed(now, ZoneOffset.UTC))));

        assertThat(resolver.begin(account)).isEqualTo(SocialUnlinkResult.APPLE_TOKEN_UNAVAILABLE);
        verifyNoInteractions(client);
    }
}
