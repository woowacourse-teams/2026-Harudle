package com.harudle.feed.service;

import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.feed.repository.FeedLikeRepository;
import com.harudle.feed.service.dto.FeedLikeResult;
import com.harudle.feed.service.port.FeedInteractionPort;
import com.harudle.feed.service.port.FeedInteractionPort.CounterChange;
import com.harudle.feed.service.port.FeedInteractionPort.CounterKind;
import com.harudle.feed.service.port.FeedInteractionPort.Counts;
import com.harudle.feed.service.port.FeedInteractionPort.Target;
import com.harudle.notification.service.port.InAppNotificationRecorder;
import com.harudle.notification.service.port.InAppNotificationRecorder.Interaction;
import com.harudle.notification.service.port.InAppNotificationRecorder.Type;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedLikeService {

    private final FeedInteractionPort feedInteractionPort;
    private final FeedLikeRepository feedLikeRepository;
    private final InAppNotificationRecorder notificationRecorder;
    private final Clock clock;

    public FeedLikeService(
            FeedInteractionPort feedInteractionPort,
            FeedLikeRepository feedLikeRepository,
            InAppNotificationRecorder notificationRecorder,
            @Qualifier("serviceClock") Clock clock
    ) {
        this.feedInteractionPort = Objects.requireNonNull(feedInteractionPort);
        this.feedLikeRepository = Objects.requireNonNull(feedLikeRepository);
        this.notificationRecorder = Objects.requireNonNull(notificationRecorder);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FeedLikeResult like(UUID userId, UUID feedId) {
        validateMember(userId, feedId);
        Target target = feedInteractionPort.lockActiveForInteraction(feedId, userId);
        UUID likeId = UUID.randomUUID();
        Instant now = clock.instant();
        if (!feedLikeRepository.add(likeId, feedId, userId, now)) {
            return new FeedLikeResult(true, target.counts().likeCount());
        }
        Counts counts = feedInteractionPort.adjustCounter(feedId, CounterKind.LIKE, CounterChange.ADDED);
        notificationRecorder.record(new Interaction(likeId, Type.LIKE, feedId, userId, target.authorId(), now));
        return new FeedLikeResult(true, counts.likeCount());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FeedLikeResult unlike(UUID userId, UUID feedId) {
        validateMember(userId, feedId);
        Target target = feedInteractionPort.lockActiveForInteraction(feedId, userId);
        if (!feedLikeRepository.remove(feedId, userId)) {
            return new FeedLikeResult(false, target.counts().likeCount());
        }
        Counts counts = feedInteractionPort.adjustCounter(feedId, CounterKind.LIKE, CounterChange.REMOVED);
        return new FeedLikeResult(false, counts.likeCount());
    }

    private void validateMember(UUID userId, UUID feedId) {
        Objects.requireNonNull(feedId, "피드 ID는 필수입니다.");
        if (userId == null || !feedLikeRepository.isActiveMember(userId)) {
            throw new AuthenticationRequiredException();
        }
    }
}
