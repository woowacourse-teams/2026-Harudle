package com.harudle.auth.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.auth.application.WithdrawalDatabaseService.WithdrawalGenerationInProgressException;
import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.WithdrawalImageRepository.ImageTargets;
import com.harudle.auth.infrastructure.oauth.SocialUnlinkResolver;
import com.harudle.auth.infrastructure.oauth.SocialUnlinkResult;
import com.harudle.generation.diary.service.DiaryGenerationStatusQueryService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;

class WithdrawalServiceTest {

    @Test
    void unlinksBeforeDeletingAndDeletesImagesAfterDatabase() {
        var f = new Fixture();
        var targets = new ImageTargets(List.of("diary"), List.of());
        when(f.unlink.unlink(any(), any())).thenReturn(SocialUnlinkResult.REVOKED);
        when(f.database.delete(f.userId)).thenReturn(targets);

        f.service.completeGoogle(f.userId, f.authentication, f.client);

        var order = inOrder(f.unlink, f.database, f.images);
        order.verify(f.unlink).unlink(any(), any());
        order.verify(f.database).delete(f.userId);
        order.verify(f.images).delete(targets);
    }

    @Test
    void doesNotUnlinkWhileGenerationIsRunning() {
        var f = new Fixture();
        when(f.generations.hasUnfinishedGeneration(f.userId)).thenReturn(true);

        assertThatThrownBy(() -> f.service.completeGoogle(f.userId, f.authentication, f.client))
                .isInstanceOf(WithdrawalGenerationInProgressException.class);
        verifyNoInteractions(f.unlink, f.database, f.images);
    }

    @Test
    void doesNotDeleteWhenUnlinkFails() {
        var f = new Fixture();
        when(f.unlink.unlink(any(), any())).thenThrow(new IllegalStateException());

        assertThatThrownBy(() -> f.service.completeGoogle(f.userId, f.authentication, f.client))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(f.database, f.images);
    }

    @Test
    void doesNotDeleteImagesWhenDatabaseDeletionFails() {
        var f = new Fixture();
        when(f.unlink.unlink(any(), any())).thenReturn(SocialUnlinkResult.REVOKED);
        when(f.database.delete(f.userId)).thenThrow(new IllegalStateException());

        assertThatThrownBy(() -> f.service.completeGoogle(f.userId, f.authentication, f.client))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(f.images);
    }

    @Test
    void retriesAfterMemberRequestsWithdrawalAgain() {
        var f = new Fixture();
        var targets = new ImageTargets(List.of(), List.of());
        when(f.unlink.unlink(any(), any())).thenReturn(SocialUnlinkResult.REVOKED);
        when(f.unlink.begin(any())).thenReturn(SocialUnlinkResult.GOOGLE_AUTHENTICATION_REQUIRED);
        when(f.database.delete(f.userId)).thenThrow(new IllegalStateException()).thenReturn(targets);

        assertThatThrownBy(() -> f.service.completeGoogle(f.userId, f.authentication, f.client))
                .isInstanceOf(IllegalStateException.class);
        verify(f.database).delete(f.userId);
        verifyNoInteractions(f.images);

        assertThat(f.service.begin(f.userId)).isEqualTo(SocialUnlinkResult.GOOGLE_AUTHENTICATION_REQUIRED);
        f.service.completeGoogle(f.userId, f.authentication, f.client);

        verify(f.database, times(2)).delete(f.userId);
        verify(f.images).delete(targets);
    }

    @Test
    void googleStartWaitsForReauthenticationWithoutDeleting() {
        var f = new Fixture();
        when(f.unlink.begin(any())).thenReturn(SocialUnlinkResult.GOOGLE_AUTHENTICATION_REQUIRED);

        assertThat(f.service.begin(f.userId)).isEqualTo(SocialUnlinkResult.GOOGLE_AUTHENTICATION_REQUIRED);
        verifyNoInteractions(f.database, f.images, f.authorizedClients);
    }

    @Test
    void appleWithoutTokenDeletesAndReturnsManualGuidance() {
        var f = new Fixture();
        when(f.account.getProvider()).thenReturn(OAuthProvider.APPLE);
        var targets = new ImageTargets(List.of(), List.of());
        when(f.unlink.begin(any())).thenReturn(SocialUnlinkResult.APPLE_TOKEN_UNAVAILABLE);
        when(f.database.delete(f.userId)).thenReturn(targets);

        assertThat(f.service.begin(f.userId)).isEqualTo(SocialUnlinkResult.APPLE_TOKEN_UNAVAILABLE);
        var order = inOrder(f.unlink, f.database, f.images);
        order.verify(f.unlink).begin(any());
        order.verify(f.database).delete(f.userId);
        order.verify(f.images).delete(targets);
    }

    private static class Fixture {
        private final UUID userId = UUID.randomUUID();
        private final OAuthAccountRepository accounts = mock(OAuthAccountRepository.class);
        private final DiaryGenerationStatusQueryService generations = mock(DiaryGenerationStatusQueryService.class);
        private final SocialUnlinkResolver unlink = mock(SocialUnlinkResolver.class);
        private final WithdrawalDatabaseService database = mock(WithdrawalDatabaseService.class);
        private final WithdrawalImageCleanupService images = mock(WithdrawalImageCleanupService.class);
        private final OAuth2AuthorizedClientService authorizedClients = mock(OAuth2AuthorizedClientService.class);
        private final OAuth2AuthenticationToken authentication = mock(OAuth2AuthenticationToken.class);
        private final OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
        private final OAuthAccount account = mock(OAuthAccount.class);
        private final WithdrawalService service =
                new WithdrawalService(accounts, generations, unlink, database, images, authorizedClients);

        private Fixture() {
            when(account.getProvider()).thenReturn(OAuthProvider.GOOGLE);
            when(accounts.findByUser_Id(userId)).thenReturn(Optional.of(account));
        }
    }
}
