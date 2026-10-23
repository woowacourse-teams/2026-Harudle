package com.harudle.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.feed.repository.FeedLikeRepository;
import com.harudle.feed.service.dto.FeedLikeResult;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FeedLikeServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AUTHOR_ID = UUID.randomUUID();
    private static final UUID FEED_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-12T01:00:00Z");

    @Mock
    private FeedInteractionPort feedInteractionPort;
    @Mock
    private FeedLikeRepository feedLikeRepository;
    @Mock
    private InAppNotificationRecorder notificationRecorder;

    private FeedLikeService service;

    @BeforeEach
    void setUp() {
        service = new FeedLikeService(feedInteractionPort, feedLikeRepository, notificationRecorder,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void addsLikeThenCounterAndNotificationWithTheSavedActionId() {
        activeTarget(AUTHOR_ID, 12);
        when(feedLikeRepository.add(any(), eq(FEED_ID), eq(USER_ID), eq(NOW))).thenReturn(true);
        when(feedInteractionPort.adjustCounter(FEED_ID, CounterKind.LIKE, CounterChange.ADDED))
                .thenReturn(new Counts(13, 3));
        when(notificationRecorder.record(any())).thenReturn(Result.CREATED);

        assertThat(service.like(USER_ID, FEED_ID)).isEqualTo(new FeedLikeResult(true, 13));

        ArgumentCaptor<UUID> savedLikeId = ArgumentCaptor.forClass(UUID.class);
        var order = inOrder(feedLikeRepository, feedInteractionPort, notificationRecorder);
        order.verify(feedLikeRepository).isActiveMember(USER_ID);
        order.verify(feedInteractionPort).lockActiveForInteraction(FEED_ID, USER_ID);
        order.verify(feedLikeRepository).add(savedLikeId.capture(), eq(FEED_ID), eq(USER_ID), eq(NOW));
        order.verify(feedInteractionPort).adjustCounter(FEED_ID, CounterKind.LIKE, CounterChange.ADDED);
        order.verify(notificationRecorder).record(new Interaction(savedLikeId.getValue(), Type.LIKE,
                FEED_ID, USER_ID, AUTHOR_ID, NOW));
    }

    @Test
    void repeatedPutReturnsLockedCountWithoutChangingCounterOrRecordingNotification() {
        activeTarget(AUTHOR_ID, 12);

        assertThat(service.like(USER_ID, FEED_ID)).isEqualTo(new FeedLikeResult(true, 12));

        verify(feedInteractionPort, never()).adjustCounter(any(), any(), any());
        verifyNoInteractions(notificationRecorder);
    }

    @Test
    void selfSkippedNotificationStillCommitsLikeNormally() {
        activeTarget(USER_ID, 0);
        when(feedLikeRepository.add(any(), eq(FEED_ID), eq(USER_ID), eq(NOW))).thenReturn(true);
        when(feedInteractionPort.adjustCounter(FEED_ID, CounterKind.LIKE, CounterChange.ADDED))
                .thenReturn(new Counts(1, 0));
        when(notificationRecorder.record(any())).thenReturn(Result.SELF_SKIPPED);

        assertThat(service.like(USER_ID, FEED_ID)).isEqualTo(new FeedLikeResult(true, 1));
    }

    @Test
    void duplicateNotificationIsNotAnInteractionFailure() {
        activeTarget(AUTHOR_ID, 0);
        when(feedLikeRepository.add(any(), eq(FEED_ID), eq(USER_ID), eq(NOW))).thenReturn(true);
        when(feedInteractionPort.adjustCounter(FEED_ID, CounterKind.LIKE, CounterChange.ADDED))
                .thenReturn(new Counts(1, 0));
        when(notificationRecorder.record(any())).thenReturn(Result.DUPLICATE);

        assertThat(service.like(USER_ID, FEED_ID)).isEqualTo(new FeedLikeResult(true, 1));
    }

    @Test
    void removesOnlyTheAuthenticatedUsersLikeAndDoesNotRecordNotification() {
        activeTarget(AUTHOR_ID, 12);
        when(feedLikeRepository.remove(FEED_ID, USER_ID)).thenReturn(true);
        when(feedInteractionPort.adjustCounter(FEED_ID, CounterKind.LIKE, CounterChange.REMOVED))
                .thenReturn(new Counts(11, 3));

        assertThat(service.unlike(USER_ID, FEED_ID)).isEqualTo(new FeedLikeResult(false, 11));

        verify(feedLikeRepository).remove(FEED_ID, USER_ID);
        verifyNoInteractions(notificationRecorder);
    }

    @Test
    void repeatedDeleteReturnsLockedCountWithoutDecrement() {
        activeTarget(AUTHOR_ID, 0);

        assertThat(service.unlike(USER_ID, FEED_ID)).isEqualTo(new FeedLikeResult(false, 0));

        verify(feedInteractionPort, never()).adjustCounter(any(), any(), any());
        verifyNoInteractions(notificationRecorder);
    }

    @Test
    void rejectsMissingInactiveAndGuestActorsBeforeLookingUpTheFeed() {
        assertThatThrownBy(() -> service.like(USER_ID, FEED_ID))
                .isInstanceOf(AuthenticationRequiredException.class);
        assertThatThrownBy(() -> service.unlike(USER_ID, FEED_ID))
                .isInstanceOf(AuthenticationRequiredException.class);
        assertThatThrownBy(() -> service.like(null, FEED_ID))
                .isInstanceOf(AuthenticationRequiredException.class);

        verifyNoInteractions(feedInteractionPort, notificationRecorder);
    }

    @Test
    void rejectsUnavailableFeedBeforeWritingLikeOrNotification() {
        when(feedLikeRepository.isActiveMember(USER_ID)).thenReturn(true);
        when(feedInteractionPort.lockActiveForInteraction(FEED_ID, USER_ID)).thenThrow(new FeedNotFoundException());

        assertThatThrownBy(() -> service.like(USER_ID, FEED_ID)).isInstanceOf(FeedNotFoundException.class);
        assertThatThrownBy(() -> service.unlike(USER_ID, FEED_ID)).isInstanceOf(FeedNotFoundException.class);

        verify(feedLikeRepository, never()).add(any(), any(), any(), any());
        verify(feedLikeRepository, never()).remove(any(), any());
        verifyNoInteractions(notificationRecorder);
    }

    @Test
    void propagatesCounterAndRecorderFailuresSoTheTransactionCanRollBack() {
        activeTarget(AUTHOR_ID, 0);
        when(feedLikeRepository.add(any(), eq(FEED_ID), eq(USER_ID), eq(NOW))).thenReturn(true);
        when(feedInteractionPort.adjustCounter(FEED_ID, CounterKind.LIKE, CounterChange.ADDED))
                .thenReturn(new Counts(1, 0));
        when(notificationRecorder.record(any())).thenThrow(new IllegalStateException("record failed"));

        assertThatThrownBy(() -> service.like(USER_ID, FEED_ID))
                .isInstanceOf(IllegalStateException.class).hasMessage("record failed");
    }

    @Test
    void rejectsNullFeedIdWithoutQueryingStorage() {
        assertThatThrownBy(() -> service.like(USER_ID, null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(feedLikeRepository, feedInteractionPort, notificationRecorder);
    }

    @Test
    void doesNotAllowNegativeCountResults() {
        assertThatThrownBy(() -> new FeedLikeResult(true, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private void activeTarget(UUID authorId, int likeCount) {
        when(feedLikeRepository.isActiveMember(USER_ID)).thenReturn(true);
        when(feedInteractionPort.lockActiveForInteraction(FEED_ID, USER_ID)).thenReturn(new Target(FEED_ID, authorId, new Counts(likeCount, 3)));
    }
}
