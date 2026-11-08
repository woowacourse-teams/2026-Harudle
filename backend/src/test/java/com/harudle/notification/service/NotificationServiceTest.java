package com.harudle.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.notification.query.NotificationCursor;
import com.harudle.notification.repository.NotificationReadTarget;
import com.harudle.notification.repository.NotificationRepository;
import com.harudle.notification.repository.NotificationSnapshot;
import com.harudle.notification.service.exception.NotificationAccessDeniedException;
import com.harudle.notification.service.exception.NotificationIntegrationUnavailableException;
import com.harudle.notification.service.exception.NotificationNotFoundException;
import com.harudle.notification.service.port.InAppNotificationRecorder.Type;
import com.harudle.profile.service.port.PublicProfileReader;
import com.harudle.profile.service.port.PublicProfileReader.Profile;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
    private static final UUID RECIPIENT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID FEED = UUID.randomUUID();
    private static final UUID FIRST = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
    private static final Profile PROFILE = new Profile(
            ACTOR, "네오", URI.create("https://example.com/profile.svg"), null
    );

    @Mock private NotificationRepository repository;
    @Mock private ObjectProvider<PublicProfileReader> profileProvider;
    @Mock private PublicProfileReader profiles;
    @Mock private NotificationCursorCodec codec;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(repository, profileProvider, codec, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void emptyPageStillReturnsGlobalUnreadCountWithoutProfileCall() {
        listReady();
        when(repository.findAfter(RECIPIENT, Optional.empty(), 21)).thenReturn(List.of());
        when(repository.countUnread(RECIPIENT)).thenReturn(37L);
        var page = service.list(RECIPIENT, null, 20);
        assertThat(page.items()).isEmpty();
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.unreadCount()).isEqualTo(37);
        verifyNoInteractions(profileProvider, profiles);
    }

    @Test
    void readsActiveActorsInOneBatchAndUsesLastReturnedNotificationForCursor() {
        listReady();
        UUID second = UUID.randomUUID();
        UUID lookAhead = UUID.randomUUID();
        UUID unreturnedActor = UUID.randomUUID();
        when(repository.findAfter(RECIPIENT, Optional.empty(), 3)).thenReturn(List.of(
                snapshot(FIRST, ACTOR, Type.LIKE, null), snapshot(second, ACTOR, Type.COMMENT, NOW),
                snapshot(lookAhead, unreturnedActor, Type.LIKE, null)));
        when(repository.countUnread(RECIPIENT)).thenReturn(9L);
        availableProfile(Map.of(ACTOR, PROFILE));
        when(codec.encode(new NotificationCursor(RECIPIENT, NOW, second))).thenReturn("next");
        var page = service.list(RECIPIENT, null, 2);
        assertThat(page.items()).extracting(item -> item.id()).containsExactly(FIRST, second);
        assertThat(page.items().getFirst().message()).isEqualTo("네오님이 좋아요를 눌렀습니다.");
        assertThat(page.items().getLast().message()).isEqualTo("네오님이 댓글을 남겼습니다.");
        assertThat(page.items().getFirst().read()).isFalse();
        assertThat(page.items().getLast().read()).isTrue();
        assertThat(page.hasNext()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("next");
        assertThat(page.unreadCount()).isEqualTo(9);
        verify(profiles).readAll(Set.of(ACTOR));
    }

    @Test
    void withdrawnActorsAreNullableAndDoNotRequireProfileIntegration() {
        listReady();
        UUID second = UUID.randomUUID();
        when(repository.findAfter(RECIPIENT, Optional.empty(), 21)).thenReturn(List.of(
                snapshot(FIRST, null, Type.LIKE, null), snapshot(second, null, Type.COMMENT, NOW)));
        var items = service.list(RECIPIENT, null, 20).items();
        assertThat(items).allMatch(item -> item.actor() == null);
        assertThat(items.getFirst().message()).isEqualTo("탈퇴한 회원이 좋아요를 눌렀습니다.");
        assertThat(items.getLast().message()).isEqualTo("탈퇴한 회원이 댓글을 남겼습니다.");
        verifyNoInteractions(profileProvider, profiles);
    }

    @Test
    void onlyActiveActorsAreRequestedWhenPageAlsoContainsWithdrawnActors() {
        listReady();
        when(repository.findAfter(RECIPIENT, Optional.empty(), 21)).thenReturn(List.of(
                snapshot(FIRST, null, Type.COMMENT, null), snapshot(UUID.randomUUID(), ACTOR, Type.LIKE, null)));
        availableProfile(Map.of(ACTOR, PROFILE));
        assertThat(service.list(RECIPIENT, null, 20).items().getFirst().actor()).isNull();
        verify(profiles).readAll(Set.of(ACTOR));
    }

    @Test
    void activeActorWithoutProviderFailsClosedWithExplicitIntegrationError() {
        listReady();
        activeActorPage();
        assertThatThrownBy(() -> service.list(RECIPIENT, null, 20))
                .isInstanceOf(NotificationIntegrationUnavailableException.class);
    }

    @Test
    void activeActorMissingFromProfileMapCannotBecomeWithdrawnActor() {
        listReady();
        activeActorPage();
        availableProfile(Map.of());
        assertThatThrownBy(() -> service.list(RECIPIENT, null, 20))
                .isInstanceOf(NotificationIntegrationUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/profile.svg", "javascript:alert(1)", "ftp://example.com/profile.svg", "https:profile"})
    void invalidProfileUrlsCannotBeReturned(String url) {
        listReady();
        activeActorPage();
        availableProfile(Map.of(ACTOR, new Profile(ACTOR, "네오", URI.create(url), null)));
        assertThatThrownBy(() -> service.list(RECIPIENT, null, 20))
                .isInstanceOf(NotificationIntegrationUnavailableException.class);
    }

    @Test
    void incompleteOrWrongIdentityProfilesCannotBeReturned() {
        listReady();
        activeActorPage();
        when(profileProvider.getIfAvailable()).thenReturn(profiles);
        when(profiles.readAll(Set.of(ACTOR)))
                .thenReturn(Map.of(ACTOR, new Profile(RECIPIENT, "네오", PROFILE.profileImageUrl(), null)))
                .thenReturn(Map.of(ACTOR, new Profile(ACTOR, "네오", null, null)))
                .thenReturn(Map.of(ACTOR, new Profile(ACTOR, " ", PROFILE.profileImageUrl(), null)))
                .thenReturn(null);
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.list(RECIPIENT, null, 20))
                    .isInstanceOf(NotificationIntegrationUnavailableException.class);
        }
    }

    @Test
    void unconfiguredOrInvalidActorIsRejectedBeforeListOrUpdate() {
        assertThatThrownBy(() -> service.list(RECIPIENT, null, 20))
                .isInstanceOf(AuthenticationRequiredException.class);
        assertThatThrownBy(() -> service.markRead(RECIPIENT, FIRST))
                .isInstanceOf(AuthenticationRequiredException.class);
        assertThatThrownBy(() -> service.list(null, null, 20))
                .isInstanceOf(AuthenticationRequiredException.class);
        verify(repository, never()).findAfter(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(repository, never()).findVisibleById(any());
        verifyNoInteractions(profileProvider, profiles, codec);
    }

    @Test
    void pageSizeValidationBoundsLookAheadQuery() {
        when(repository.isActiveMember(RECIPIENT)).thenReturn(true);
        assertThatThrownBy(() -> service.list(RECIPIENT, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(RECIPIENT, null, 51)).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).findAfter(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void readsOnlyTheRequestedOwnedNotificationWithoutProfileDependency() {
        when(repository.isActiveMember(RECIPIENT)).thenReturn(true);
        when(repository.findVisibleById(FIRST)).thenReturn(Optional.of(new NotificationReadTarget(FIRST, RECIPIENT)));
        when(repository.markRead(FIRST, RECIPIENT, NOW)).thenReturn(true);
        service.markRead(RECIPIENT, FIRST);
        service.markRead(RECIPIENT, FIRST);
        verify(repository, times(2)).markRead(FIRST, RECIPIENT, NOW);
        verifyNoInteractions(profileProvider, profiles, codec);
    }

    @Test
    void anotherRecipientsNotificationIsForbidden() {
        when(repository.isActiveMember(RECIPIENT)).thenReturn(true);
        when(repository.findVisibleById(FIRST)).thenReturn(Optional.of(new NotificationReadTarget(FIRST, ACTOR)));
        assertThatThrownBy(() -> service.markRead(RECIPIENT, FIRST)).isInstanceOf(NotificationAccessDeniedException.class);
        verify(repository, never()).markRead(any(), any(), any());
    }

    @Test
    void missingOrDeletedParentNotificationIsNotFound() {
        when(repository.isActiveMember(RECIPIENT)).thenReturn(true);
        when(repository.findVisibleById(FIRST)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.markRead(RECIPIENT, FIRST)).isInstanceOf(NotificationNotFoundException.class);
        verify(repository, never()).markRead(any(), any(), any());
    }

    @Test
    void cancelledLikeNotificationIsNotReadableEvenWhileDatabaseHistoryRemains() {
        when(repository.isActiveMember(RECIPIENT)).thenReturn(true);
        when(repository.findVisibleById(FIRST)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(RECIPIENT, FIRST))
                .isInstanceOf(NotificationNotFoundException.class);

        verify(repository, never()).markRead(any(), any(), any());
        verifyNoInteractions(profileProvider, profiles, codec);
    }

    @Test
    void updateLosingVisibilityRaceReturns404RatherThanPretendingSuccess() {
        when(repository.isActiveMember(RECIPIENT)).thenReturn(true);
        when(repository.findVisibleById(FIRST)).thenReturn(Optional.of(new NotificationReadTarget(FIRST, RECIPIENT)));
        when(repository.markRead(FIRST, RECIPIENT, NOW)).thenReturn(false);
        assertThatThrownBy(() -> service.markRead(RECIPIENT, FIRST)).isInstanceOf(NotificationNotFoundException.class);
    }

    @Test
    void usesRepeatableReadTransactionForPageAndCount() throws Exception {
        Transactional transaction = NotificationService.class.getMethod("list", UUID.class, String.class, int.class)
                .getAnnotation(Transactional.class);
        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    private void listReady() {
        when(repository.isActiveMember(RECIPIENT)).thenReturn(true);
        when(codec.decode(RECIPIENT, null)).thenReturn(Optional.empty());
    }

    private void activeActorPage() {
        when(repository.findAfter(RECIPIENT, Optional.empty(), 21))
                .thenReturn(List.of(snapshot(FIRST, ACTOR, Type.LIKE, null)));
    }

    private void availableProfile(Map<UUID, Profile> map) {
        when(profileProvider.getIfAvailable()).thenReturn(profiles);
        when(profiles.readAll(Set.of(ACTOR))).thenReturn(map);
    }

    private NotificationSnapshot snapshot(UUID id, UUID actor, Type type, Instant readAt) {
        return new NotificationSnapshot(id, RECIPIENT, actor, FEED, type, NOW, readAt);
    }
}
