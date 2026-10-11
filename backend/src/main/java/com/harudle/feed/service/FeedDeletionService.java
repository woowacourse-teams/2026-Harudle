package com.harudle.feed.service;

import com.harudle.feed.domain.Feed;
import com.harudle.feed.repository.FeedDeletionRepository;
import com.harudle.feed.repository.FeedInteractionRepository;
import com.harudle.feed.service.exception.FeedAccessDeniedException;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.feed.service.port.FeedLifecycle;
import com.harudle.push.service.port.FeedPushOutbox;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedDeletionService implements FeedLifecycle {
    private final FeedInteractionRepository feeds;
    private final FeedDeletionRepository deletions;
    private final FeedCollaborators collaborators;
    private final Clock clock;

    public FeedDeletionService(
            FeedInteractionRepository feeds,
            FeedDeletionRepository deletions,
            FeedCollaborators collaborators,
            @Qualifier("serviceClock") Clock clock
    ) {
        this.feeds = feeds;
        this.deletions = deletions;
        this.collaborators = collaborators;
        this.clock = clock;
    }

    @Transactional
    public void delete(UUID actorId, UUID feedId) {
        Objects.requireNonNull(actorId, "사용자 ID는 필수입니다.");
        Objects.requireNonNull(feedId, "피드 ID는 필수입니다.");
        Feed feed = feeds.findByIdForUpdate(feedId).orElseThrow(FeedNotFoundException::new);
        if (feed.isDeleted()) {
            throw new FeedNotFoundException();
        }
        UUID authorId = feeds.findActiveAuthorId(feed.getDiaryId()).orElseThrow(FeedNotFoundException::new);
        if (!authorId.equals(actorId)) {
            throw new FeedAccessDeniedException();
        }
        deleteLocked(feed, clock.instant());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteByDiary(UUID diaryId, Instant deletedAt) {
        Objects.requireNonNull(diaryId, "일기 ID는 필수입니다.");
        Objects.requireNonNull(deletedAt, "삭제 시각은 필수입니다.");
        // 일기 잠금과 소유권 확인은 호출자가 수행한다. 여기서는 연결 피드만 잠근다.
        deletions.findActiveByDiaryIdForUpdate(diaryId).ifPresent(feed -> deleteLocked(feed, deletedAt));
    }

    private void deleteLocked(Feed feed, Instant deletedAt) {
        if (feed.isDeleted()) {
            return;
        }
        FeedPushOutbox pushOutbox = collaborators.pushOutbox();
        feed.delete(deletedAt);
        pushOutbox.cancelUnsentByFeed(feed.getId());
    }
}
