package com.harudle.feed.service;

import com.harudle.category.service.port.CategoryReader;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.diary.service.port.DiaryPublicationReader;
import com.harudle.feed.domain.Feed;
import com.harudle.feed.repository.FeedRepository;
import com.harudle.feed.service.dto.FeedResult;
import com.harudle.feed.service.exception.DiaryAlreadyPublishedException;
import com.harudle.profile.service.port.PublicProfileReader;
import com.harudle.push.service.port.FeedPushOutbox;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedPublicationService {

    private final DiaryPublicationReader diaryPublicationReader;
    private final FeedRepository feeds;
    private final FeedCollaborators collaborators;
    private final Clock clock;

    public FeedPublicationService(
            DiaryPublicationReader diaryPublicationReader,
            FeedRepository feeds,
            FeedCollaborators collaborators,
            @Qualifier("serviceClock") Clock clock
    ) {
        this.diaryPublicationReader = diaryPublicationReader;
        this.feeds = feeds;
        this.collaborators = collaborators;
        this.clock = clock;
    }

    @Transactional
    public FeedResult publish(UUID actorId, UUID diaryId, long categoryId) {
        Objects.requireNonNull(actorId, "사용자 ID는 필수입니다.");
        Objects.requireNonNull(diaryId, "일기 ID는 필수입니다.");
        if (categoryId <= 0) {
            throw new IllegalArgumentException("카테고리 ID는 양수여야 합니다.");
        }
        // 실제 예약 구현이 없으면 쓰기를 시작하기 전에 명확하게 실패한다.
        FeedPushOutbox pushOutbox = collaborators.pushOutbox();
        CategoryReader categories = collaborators.categories();
        PublicProfileReader profiles = collaborators.profiles();
        DiaryPublicationReader.PublishableDiary diary = diaryPublicationReader.lockAndRead(actorId, diaryId);
        CategoryReader.Category category = categories.lockActiveForPublication(categoryId);
        if (feeds.existsByDiaryIdAndDeletedAtIsNull(diaryId)) {
            throw new DiaryAlreadyPublishedException();
        }
        PublicProfileReader.Profile author = profiles.readAll(Set.of(diary.authorId())).get(diary.authorId());
        if (author == null) {
            throw new DiaryAccessDeniedException();
        }
        Feed feed = Feed.publish(diary.diaryId(), category.id(), clock.instant());
        save(feed);
        pushOutbox.enqueuePublished(new FeedPushOutbox.FeedPublished(
                UUID.randomUUID(), feed.getId(), feed.getPublishedAt()
        ));
        return new FeedResult(
                feed.getId(), author, category, diary.imageObjectKey(), feed.getPublishedAt(),
                feed.getLikeCount(), feed.getCommentCount(), false, true
        );
    }

    private void save(Feed feed) {
        try {
            feeds.saveAndFlush(feed);
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "uq_feeds_active_diary".equals(violation.getConstraintName())) {
                    throw new DiaryAlreadyPublishedException();
                }
            }
            throw exception;
        }
    }
}
