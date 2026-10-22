package com.harudle.comment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.comment.query.CommentCursor;
import com.harudle.comment.repository.CommentRepository;
import com.harudle.comment.repository.CommentSnapshot;
import com.harudle.comment.service.exception.CommentAccessDeniedException;
import com.harudle.comment.service.exception.CommentIntegrationUnavailableException;
import com.harudle.comment.service.exception.CommentNotFoundException;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.feed.service.port.FeedInteractionPort;
import com.harudle.feed.service.port.FeedInteractionPort.CounterChange;
import com.harudle.feed.service.port.FeedInteractionPort.CounterKind;
import com.harudle.feed.service.port.FeedInteractionPort.Counts;
import com.harudle.feed.service.port.FeedInteractionPort.Target;
import com.harudle.notification.service.port.InAppNotificationRecorder;
import com.harudle.notification.service.port.InAppNotificationRecorder.Interaction;
import com.harudle.notification.service.port.InAppNotificationRecorder.Result;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID AUTHOR_ID = UUID.randomUUID();
    private static final UUID FEED_ID = UUID.randomUUID();
    private static final UUID COMMENT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-12T02:00:00Z");
    private static final Profile PROFILE = new Profile(ACTOR_ID, "연준", URI.create("https://images.example.com/profile.svg"));

    @Mock private CommentRepository repository;
    @Mock private FeedInteractionPort feeds;
    @Mock private InAppNotificationRecorder notifications;
    @Mock private PublicProfileReader profiles;
    @Mock private ObjectProvider<PublicProfileReader> profileProvider;
    @Mock private CommentCursorCodec codec;
    private CommentService service;

    @BeforeEach
    void setUp() {
        service = new CommentService(repository, feeds, notifications, profileProvider, codec,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsNormalizedPlainTextAndUsesSavedCommentForNotification() {
        activeActor();
        when(feeds.lockActiveForInteraction(FEED_ID, ACTOR_ID)).thenReturn(new Target(FEED_ID, AUTHOR_ID, new Counts(3, 0)));
        availableProfiles(Map.of(ACTOR_ID, PROFILE), Set.of(ACTOR_ID));
        when(repository.create(any(), eq(FEED_ID), eq(ACTOR_ID), eq("<b>안녕 😀</b>"), eq(NOW)))
                .thenAnswer(invocation -> new CommentSnapshot(invocation.getArgument(0), FEED_ID, ACTOR_ID,
                        "<b>안녕 😀</b>", NOW));
        when(notifications.record(any())).thenReturn(Result.CREATED);

        var result = service.create(ACTOR_ID, FEED_ID, "  <b>안녕 😀</b> \n");

        assertThat(result.content()).isEqualTo("<b>안녕 😀</b>");
        assertThat(result.author()).isEqualTo(PROFILE);
        assertThat(result.isMine()).isTrue();
        ArgumentCaptor<UUID> commentId = ArgumentCaptor.forClass(UUID.class);
        var order = inOrder(repository, feeds, profiles, notifications);
        order.verify(repository).isActiveMember(ACTOR_ID);
        order.verify(feeds).lockActiveForInteraction(FEED_ID, ACTOR_ID);
        order.verify(profiles).readAll(Set.of(ACTOR_ID));
        order.verify(repository).create(commentId.capture(), eq(FEED_ID), eq(ACTOR_ID), eq("<b>안녕 😀</b>"), eq(NOW));
        order.verify(feeds).adjustCounter(FEED_ID, CounterKind.COMMENT, CounterChange.ADDED);
        order.verify(notifications).record(new Interaction(commentId.getValue(), Type.COMMENT,
                FEED_ID, ACTOR_ID, AUTHOR_ID, NOW));
        assertThat(result.id()).isEqualTo(commentId.getValue());
    }

    @Test
    void selfSkippedNotificationIsNormalCreateSuccess() {
        activeActor();
        when(feeds.lockActiveForInteraction(FEED_ID, ACTOR_ID)).thenReturn(new Target(FEED_ID, ACTOR_ID, new Counts(0, 0)));
        availableProfiles(Map.of(ACTOR_ID, PROFILE), Set.of(ACTOR_ID));
        when(repository.create(any(), any(), any(), eq("안녕"), eq(NOW)))
                .thenReturn(snapshot(COMMENT_ID, ACTOR_ID));
        when(notifications.record(any())).thenReturn(Result.SELF_SKIPPED);
        var created = service.create(ACTOR_ID, FEED_ID, "안녕");
        assertThat(created.id()).isEqualTo(COMMENT_ID);
        assertThat(created.author()).isEqualTo(PROFILE);
        assertThat(created.isMine()).isTrue();
    }

    @Test
    void missingProfileProviderFailsBeforeCreateCounterOrNotification() {
        activeActor();
        when(feeds.lockActiveForInteraction(FEED_ID, ACTOR_ID)).thenReturn(new Target(FEED_ID, AUTHOR_ID, new Counts(0, 0)));
        assertThatThrownBy(() -> service.create(ACTOR_ID, FEED_ID, "안녕"))
                .isInstanceOf(CommentIntegrationUnavailableException.class);
        verify(repository, never()).create(any(), any(), any(), any(), any());
        verify(feeds, never()).adjustCounter(any(), any(), any());
        verifyNoInteractions(notifications);
    }

    @Test
    void missingAuthorFailsClosedRatherThanCreatingAnAnonymousProfile() {
        activeActor();
        when(feeds.lockActiveForInteraction(FEED_ID, ACTOR_ID)).thenReturn(new Target(FEED_ID, AUTHOR_ID, new Counts(0, 0)));
        availableProfiles(Map.of(), Set.of(ACTOR_ID));
        assertThatThrownBy(() -> service.create(ACTOR_ID, FEED_ID, "안녕"))
                .isInstanceOf(CommentIntegrationUnavailableException.class);
        verify(repository, never()).create(any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/profiles/one.svg", "javascript:alert(1)", "ftp://example.com/profile.svg", "https:profile"})
    void rejectsNonHttpOrRelativeProfileUrls(String url) {
        activeActor();
        when(feeds.lockActiveForInteraction(FEED_ID, ACTOR_ID)).thenReturn(new Target(FEED_ID, AUTHOR_ID, new Counts(0, 0)));
        availableProfiles(Map.of(ACTOR_ID, new Profile(ACTOR_ID, "연준", URI.create(url))), Set.of(ACTOR_ID));
        assertThatThrownBy(() -> service.create(ACTOR_ID, FEED_ID, "안녕"))
                .isInstanceOf(CommentIntegrationUnavailableException.class);
        verify(repository, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsProfileIdentityMismatchAndNullImage() {
        activeActor();
        when(feeds.lockActiveForInteraction(FEED_ID, ACTOR_ID)).thenReturn(new Target(FEED_ID, AUTHOR_ID, new Counts(0, 0)));
        when(profileProvider.getIfAvailable()).thenReturn(profiles);
        when(profiles.readAll(Set.of(ACTOR_ID)))
                .thenReturn(Map.of(ACTOR_ID, new Profile(AUTHOR_ID, "다른 회원", PROFILE.profileImageUrl())))
                .thenReturn(Map.of(ACTOR_ID, new Profile(ACTOR_ID, "연준", null)));
        assertThatThrownBy(() -> service.create(ACTOR_ID, FEED_ID, "안녕"))
                .isInstanceOf(CommentIntegrationUnavailableException.class);
        assertThatThrownBy(() -> service.create(ACTOR_ID, FEED_ID, "안녕"))
                .isInstanceOf(CommentIntegrationUnavailableException.class);
    }

    @Test
    void emptyPageDoesNotRequireProfileIntegration() {
        visibleFeedAndCursor(Optional.empty());
        when(repository.findAfter(eq(FEED_ID), eq(Optional.empty()), eq(21), nullable(UUID.class))).thenReturn(List.of());
        var page = service.list(FEED_ID, null, null, 20);
        verify(repository).findAfter(FEED_ID, Optional.empty(), 21, null);
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.hasNext()).isFalse();
        verifyNoInteractions(profileProvider, profiles);
    }

    @Test
    void withdrawnAuthorsRemainInThePageWithoutProfileIntegrationOrOwnership() {
        visibleFeedAndCursor(Optional.empty());
        UUID secondId = UUID.randomUUID();
        when(repository.findAfter(eq(FEED_ID), eq(Optional.empty()), eq(21), nullable(UUID.class))).thenReturn(List.of(
                withdrawnSnapshot(COMMENT_ID, ACTOR_ID), withdrawnSnapshot(secondId, AUTHOR_ID)));

        var page = service.list(FEED_ID, ACTOR_ID, null, 20);

        assertThat(page.items()).extracting(item -> item.id()).containsExactly(COMMENT_ID, secondId);
        assertThat(page.items()).allSatisfy(item -> {
            assertThat(item.author()).isNull();
            assertThat(item.isMine()).isFalse();
            assertThat(item.content()).isEqualTo("탈퇴 전에 작성한 댓글");
            assertThat(item.createdAt()).isEqualTo(NOW);
        });
        assertThat(page.nextCursor()).isNull();
        assertThat(page.hasNext()).isFalse();
        verifyNoInteractions(profileProvider, profiles, feeds, notifications);
    }

    @Test
    void mixedPageLooksUpOnlyActiveAuthorsAndKeepsWithdrawnCursorBoundary() {
        visibleFeedAndCursor(Optional.empty());
        UUID withdrawnId = UUID.randomUUID();
        UUID lookAheadId = UUID.randomUUID();
        UUID lookAheadAuthor = UUID.randomUUID();
        when(repository.findAfter(eq(FEED_ID), eq(Optional.empty()), eq(3), nullable(UUID.class))).thenReturn(List.of(
                snapshot(COMMENT_ID, ACTOR_ID), withdrawnSnapshot(withdrawnId, AUTHOR_ID),
                snapshot(lookAheadId, lookAheadAuthor)));
        // 제공자가 불필요한 과거 프로필을 함께 반환해도 DB의 탈퇴 판정을 우선해 공개하지 않는다.
        Profile oldProfile = new Profile(AUTHOR_ID, "공개하면 안 되는 이전 닉네임", PROFILE.profileImageUrl());
        availableProfiles(Map.of(ACTOR_ID, PROFILE, AUTHOR_ID, oldProfile), Set.of(ACTOR_ID));
        when(codec.encode(new CommentCursor(FEED_ID, NOW, withdrawnId))).thenReturn("next-withdrawn");

        var page = service.list(FEED_ID, ACTOR_ID, null, 2);

        assertThat(page.items()).extracting(item -> item.id()).containsExactly(COMMENT_ID, withdrawnId);
        assertThat(page.items().getFirst().author()).isEqualTo(PROFILE);
        assertThat(page.items().getFirst().isMine()).isTrue();
        assertThat(page.items().getLast().author()).isNull();
        assertThat(page.items().getLast().isMine()).isFalse();
        assertThat(page.nextCursor()).isEqualTo("next-withdrawn");
        assertThat(page.hasNext()).isTrue();
        verify(repository).findAfter(FEED_ID, Optional.empty(), 3, ACTOR_ID);
        verify(profiles).readAll(Set.of(ACTOR_ID));
        verify(codec).encode(new CommentCursor(FEED_ID, NOW, withdrawnId));
    }

    @Test
    void mixedPageStillFailsWhenRequiredActiveProfileProviderIsMissing() {
        visibleFeedAndCursor(Optional.empty());
        when(repository.findAfter(eq(FEED_ID), eq(Optional.empty()), eq(21), nullable(UUID.class))).thenReturn(List.of(
                withdrawnSnapshot(UUID.randomUUID(), AUTHOR_ID), snapshot(COMMENT_ID, ACTOR_ID)));

        assertThatThrownBy(() -> service.list(FEED_ID, null, null, 20))
                .isInstanceOf(CommentIntegrationUnavailableException.class);
        verify(profileProvider).getIfAvailable();
        verifyNoInteractions(profiles);
    }

    @Test
    void missingActiveProfileInMixedPageIsNotMistakenForAWithdrawnAuthor() {
        visibleFeedAndCursor(Optional.empty());
        when(repository.findAfter(eq(FEED_ID), eq(Optional.empty()), eq(21), nullable(UUID.class))).thenReturn(List.of(
                withdrawnSnapshot(UUID.randomUUID(), AUTHOR_ID), snapshot(COMMENT_ID, ACTOR_ID)));
        availableProfiles(Map.of(), Set.of(ACTOR_ID));

        assertThatThrownBy(() -> service.list(FEED_ID, null, null, 20))
                .isInstanceOf(CommentIntegrationUnavailableException.class);
        verify(profiles).readAll(Set.of(ACTOR_ID));
    }

    @ParameterizedTest
    @MethodSource("invalidActiveProfiles")
    void invalidActiveProfileInMixedPageStillFailsClosed(Profile invalidProfile) {
        visibleFeedAndCursor(Optional.empty());
        when(repository.findAfter(eq(FEED_ID), eq(Optional.empty()), eq(21), nullable(UUID.class))).thenReturn(List.of(
                withdrawnSnapshot(UUID.randomUUID(), AUTHOR_ID), snapshot(COMMENT_ID, ACTOR_ID)));
        availableProfiles(Map.of(ACTOR_ID, invalidProfile), Set.of(ACTOR_ID));

        assertThatThrownBy(() -> service.list(FEED_ID, null, null, 20))
                .isInstanceOf(CommentIntegrationUnavailableException.class);
    }

    @Test
    void listUsesReadOnlyRepeatableReadForAuthorClassificationAndProfiles() throws NoSuchMethodException {
        Transactional transaction = CommentService.class
                .getMethod("list", UUID.class, UUID.class, String.class, int.class)
                .getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    @Test
    void pageReadsProfilesOnceAndUsesLastReturnedCommentNotLookAheadForCursor() {
        visibleFeedAndCursor(Optional.empty());
        UUID secondId = UUID.randomUUID();
        UUID lookAheadId = UUID.randomUUID();
        UUID lookAheadAuthor = UUID.randomUUID();
        CommentSnapshot first = snapshot(COMMENT_ID, ACTOR_ID);
        CommentSnapshot second = snapshot(secondId, ACTOR_ID);
        when(repository.findAfter(eq(FEED_ID), eq(Optional.empty()), eq(3), nullable(UUID.class)))
                .thenReturn(List.of(first, second, snapshot(lookAheadId, lookAheadAuthor)));
        availableProfiles(Map.of(ACTOR_ID, PROFILE), Set.of(ACTOR_ID));
        when(codec.encode(new CommentCursor(FEED_ID, NOW, secondId))).thenReturn("next");

        var page = service.list(FEED_ID, ACTOR_ID, null, 2);

        assertThat(page.items()).extracting(item -> item.id()).containsExactly(COMMENT_ID, secondId);
        assertThat(page.items()).allMatch(item -> item.isMine());
        assertThat(page.nextCursor()).isEqualTo("next");
        assertThat(page.hasNext()).isTrue();
        verify(profiles).readAll(Set.of(ACTOR_ID));
    }

    @Test
    void anonymousAndOtherViewerAreNotTheAuthor() {
        visibleFeedAndCursor(Optional.empty());
        when(repository.findAfter(eq(FEED_ID), eq(Optional.empty()), eq(21), nullable(UUID.class)))
                .thenReturn(List.of(snapshot(COMMENT_ID, ACTOR_ID)));
        availableProfiles(Map.of(ACTOR_ID, PROFILE), Set.of(ACTOR_ID));
        assertThat(service.list(FEED_ID, null, null, 20).items().getFirst().isMine()).isFalse();
        assertThat(service.list(FEED_ID, AUTHOR_ID, null, 20).items().getFirst().isMine()).isFalse();
    }

    @Test
    void nonEmptyPageFailsWhenAProfileIsMissing() {
        visibleFeedAndCursor(Optional.empty());
        when(repository.findAfter(eq(FEED_ID), eq(Optional.empty()), eq(21), nullable(UUID.class)))
                .thenReturn(List.of(snapshot(COMMENT_ID, ACTOR_ID)));
        availableProfiles(Map.of(), Set.of(ACTOR_ID));
        assertThatThrownBy(() -> service.list(FEED_ID, null, null, 20))
                .isInstanceOf(CommentIntegrationUnavailableException.class);
    }

    @Test
    void missingFeedAndInvalidSizeDoNotQueryProfiles() {
        assertThatThrownBy(() -> service.list(FEED_ID, null, null, 20)).isInstanceOf(FeedNotFoundException.class);
        assertThatThrownBy(() -> service.list(FEED_ID, null, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(FEED_ID, null, null, 51)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(profileProvider, profiles);
    }

    @Test
    void deleteLocksFeedAndReloadsCommentBeforeCheckingOwnership() {
        activeActor();
        when(repository.findActiveById(COMMENT_ID)).thenReturn(Optional.of(snapshot(COMMENT_ID, ACTOR_ID)));
        when(repository.delete(COMMENT_ID, FEED_ID, NOW)).thenReturn(true);

        service.delete(ACTOR_ID, COMMENT_ID);

        var order = inOrder(repository, feeds);
        order.verify(repository).isActiveMember(ACTOR_ID);
        order.verify(repository).findActiveById(COMMENT_ID);
        order.verify(feeds).lockActive(FEED_ID);
        order.verify(repository).findActiveById(COMMENT_ID);
        order.verify(repository).delete(COMMENT_ID, FEED_ID, NOW);
        order.verify(feeds).adjustCounter(FEED_ID, CounterKind.COMMENT, CounterChange.REMOVED);
        verifyNoInteractions(profileProvider, profiles, notifications);
    }

    @Test
    void missingOrDeletedParentIsCommentNotFoundOnDelete() {
        activeActor();
        when(repository.findActiveById(COMMENT_ID)).thenReturn(Optional.of(snapshot(COMMENT_ID, ACTOR_ID)));
        when(feeds.lockActive(FEED_ID)).thenThrow(new FeedNotFoundException());
        assertThatThrownBy(() -> service.delete(ACTOR_ID, COMMENT_ID)).isInstanceOf(CommentNotFoundException.class);
        verify(repository, never()).delete(any(), any(), any());
        verifyNoInteractions(notifications);
    }

    @Test
    void deletingSomeoneElsesCommentIsForbiddenEvenForFeedAuthor() {
        activeActor();
        when(repository.findActiveById(COMMENT_ID)).thenReturn(Optional.of(snapshot(COMMENT_ID, AUTHOR_ID)));
        assertThatThrownBy(() -> service.delete(ACTOR_ID, COMMENT_ID)).isInstanceOf(CommentAccessDeniedException.class);
        verify(repository, never()).delete(any(), any(), any());
    }

    @Test
    void repeatedDeleteDoesNotDecrementTheCountAgain() {
        activeActor();
        when(repository.findActiveById(COMMENT_ID)).thenReturn(Optional.of(snapshot(COMMENT_ID, ACTOR_ID)));
        when(repository.delete(COMMENT_ID, FEED_ID, NOW)).thenReturn(false);
        assertThatThrownBy(() -> service.delete(ACTOR_ID, COMMENT_ID)).isInstanceOf(CommentNotFoundException.class);
        verify(feeds, never()).adjustCounter(any(), any(), any());
    }

    @Test
    void deleteRechecksThatTheCommentStillExistsAfterLocking() {
        activeActor();
        when(repository.findActiveById(COMMENT_ID))
                .thenReturn(Optional.of(snapshot(COMMENT_ID, ACTOR_ID))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(ACTOR_ID, COMMENT_ID)).isInstanceOf(CommentNotFoundException.class);
        verify(repository, never()).delete(any(), any(), any());
    }

    @Test
    void inactiveActorCannotCreateOrDelete() {
        assertThatThrownBy(() -> service.create(ACTOR_ID, FEED_ID, "안녕"))
                .isInstanceOf(AuthenticationRequiredException.class);
        assertThatThrownBy(() -> service.delete(ACTOR_ID, COMMENT_ID))
                .isInstanceOf(AuthenticationRequiredException.class);
        verifyNoInteractions(feeds, notifications, profileProvider);
    }

    @Test
    void invalidContentFailsBeforeProfileLookupOrWriting() {
        activeActor();
        assertThatThrownBy(() -> service.create(ACTOR_ID, FEED_ID, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(ACTOR_ID, FEED_ID, "😀".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(feeds, profileProvider, profiles, notifications);
    }

    private void activeActor() {
        when(repository.isActiveMember(ACTOR_ID)).thenReturn(true);
    }

    private void visibleFeedAndCursor(Optional<CommentCursor> cursor) {
        when(repository.existsVisibleFeed(eq(FEED_ID), nullable(UUID.class))).thenReturn(true);
        when(codec.decode(FEED_ID, null)).thenReturn(cursor);
    }

    private void availableProfiles(Map<UUID, Profile> result, Set<UUID> ids) {
        when(profileProvider.getIfAvailable()).thenReturn(profiles);
        when(profiles.readAll(ids)).thenReturn(result);
    }

    private CommentSnapshot snapshot(UUID id, UUID author) {
        return new CommentSnapshot(id, FEED_ID, author, "안녕", NOW);
    }

    private CommentSnapshot withdrawnSnapshot(UUID id, UUID author) {
        return new CommentSnapshot(id, FEED_ID, author, "탈퇴 전에 작성한 댓글", NOW, true);
    }

    private static Stream<Profile> invalidActiveProfiles() {
        return Stream.of(
                new Profile(AUTHOR_ID, "다른 회원", PROFILE.profileImageUrl()),
                new Profile(ACTOR_ID, null, PROFILE.profileImageUrl()),
                new Profile(ACTOR_ID, " ", PROFILE.profileImageUrl()),
                new Profile(ACTOR_ID, "연준", null),
                new Profile(ACTOR_ID, "연준", URI.create("/profiles/one.svg")),
                new Profile(ACTOR_ID, "연준", URI.create("javascript:alert(1)"))
        );
    }
}
