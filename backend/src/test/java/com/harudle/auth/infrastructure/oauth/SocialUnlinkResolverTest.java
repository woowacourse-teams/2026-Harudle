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
import org.junit.jupiter.api.Test;

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
        when(client.revoke("apple-client", null)).thenReturn(AppleTokenRevocationClient.Result.TOKEN_UNAVAILABLE);
        var resolver = new SocialUnlinkResolver(List.of(new AppleUnlinkStrategy(client)));

        assertThat(resolver.unlink(account, new SocialUnlinkCredentials.Apple("apple-client", null)))
                .isEqualTo(SocialUnlinkResult.APPLE_TOKEN_UNAVAILABLE);
    }
}
