package com.harudle.comment.service;

import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.comment.domain.CommentContent;
import com.harudle.comment.query.CommentCursor;
import com.harudle.comment.repository.CommentRepository;
import com.harudle.comment.repository.CommentSnapshot;
import com.harudle.comment.service.dto.CommentPageResult;
import com.harudle.comment.service.dto.CommentResult;
import com.harudle.comment.service.exception.CommentAccessDeniedException;
import com.harudle.comment.service.exception.CommentIntegrationUnavailableException;
import com.harudle.comment.service.exception.CommentNotFoundException;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.feed.service.port.FeedInteractionPort;
import com.harudle.feed.service.port.FeedInteractionPort.CounterChange;
import com.harudle.feed.service.port.FeedInteractionPort.CounterKind;
import com.harudle.feed.service.port.FeedInteractionPort.Target;
import com.harudle.notification.service.port.InAppNotificationRecorder;
import com.harudle.notification.service.port.InAppNotificationRecorder.Interaction;
import com.harudle.notification.service.port.InAppNotificationRecorder.Type;
import com.harudle.profile.service.port.PublicProfileReader;
import com.harudle.profile.service.port.PublicProfileReader.Profile;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommentService {

    private final CommentRepository commentRepository;
    private final FeedInteractionPort feedInteractionPort;
    private final InAppNotificationRecorder notificationRecorder;
    private final ObjectProvider<PublicProfileReader> publicProfileReaders;
    private final CommentCursorCodec cursorCodec;
    private final Clock clock;

    public CommentService(
            CommentRepository commentRepository,
            FeedInteractionPort feedInteractionPort,
            InAppNotificationRecorder notificationRecorder,
            ObjectProvider<PublicProfileReader> publicProfileReaders,
            CommentCursorCodec cursorCodec,
            @Qualifier("serviceClock") Clock clock
    ) {
        this.commentRepository = Objects.requireNonNull(commentRepository);
        this.feedInteractionPort = Objects.requireNonNull(feedInteractionPort);
        this.notificationRecorder = Objects.requireNonNull(notificationRecorder);
        this.publicProfileReaders = Objects.requireNonNull(publicProfileReaders);
        this.cursorCodec = Objects.requireNonNull(cursorCodec);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CommentResult create(UUID actorId, UUID feedId, String content) {
        validateMember(actorId);
        Objects.requireNonNull(feedId, "피드 ID는 필수입니다.");
        String normalized = CommentContent.normalize(content);
        Target target = feedInteractionPort.lockActiveForInteraction(feedId, actorId);
        Profile profile = profilesFor(Set.of(actorId)).get(actorId);
        Instant now = clock.instant();
        CommentSnapshot comment = commentRepository.create(UUID.randomUUID(), feedId, actorId, normalized, now);
        feedInteractionPort.adjustCounter(feedId, CounterKind.COMMENT, CounterChange.ADDED);
        notificationRecorder.record(new Interaction(comment.id(), Type.COMMENT, feedId, actorId, target.authorId(), now));
        return toResult(comment, profile, actorId);
    }

    // 작성자 상태와 프로필을 같은 DB 스냅샷으로 읽어, 조회 도중 탈퇴를 프로필 장애로 오인하지 않는다.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CommentPageResult list(UUID feedId, @Nullable UUID viewerId, @Nullable String cursor, int size) {
        Objects.requireNonNull(feedId, "피드 ID는 필수입니다.");
        if (size < 1 || size > 50) {
            throw new IllegalArgumentException("댓글 페이지 크기는 1~50이어야 합니다.");
        }
        if (!commentRepository.existsVisibleFeed(feedId, viewerId)) {
            throw new FeedNotFoundException();
        }
        List<CommentSnapshot> found = commentRepository.findAfter(
                feedId, cursorCodec.decode(feedId, cursor), size + 1, viewerId);
        boolean hasNext = found.size() > size;
        List<CommentSnapshot> page = found.stream().limit(size).toList();
        if (page.isEmpty()) {
            return new CommentPageResult(List.of(), null, false);
        }
        Set<UUID> authorIds = page.stream().filter(comment -> !comment.authorDeleted())
                .map(CommentSnapshot::authorId).collect(Collectors.toSet());
        Map<UUID, Profile> profiles = profilesFor(authorIds);
        List<CommentResult> items = page.stream()
                .map(comment -> toResult(comment,
                        comment.authorDeleted() ? null : profiles.get(comment.authorId()), viewerId)).toList();
        CommentSnapshot last = page.getLast();
        String nextCursor = hasNext ? cursorCodec.encode(new CommentCursor(feedId, last.createdAt(), last.id())) : null;
        return new CommentPageResult(items, nextCursor, hasNext);
    }

    @Transactional
    public void delete(UUID actorId, UUID commentId) {
        validateMember(actorId);
        Objects.requireNonNull(commentId, "댓글 ID는 필수입니다.");
        CommentSnapshot initial = commentRepository.findActiveById(commentId).orElseThrow(CommentNotFoundException::new);
        try {
            feedInteractionPort.lockActive(initial.feedId());
        } catch (FeedNotFoundException exception) {
            throw new CommentNotFoundException();
        }
        CommentSnapshot current = commentRepository.findActiveById(commentId).orElseThrow(CommentNotFoundException::new);
        if (!initial.feedId().equals(current.feedId())) {
            throw new CommentNotFoundException();
        }
        if (!actorId.equals(current.authorId())) {
            throw new CommentAccessDeniedException();
        }
        if (!commentRepository.delete(commentId, current.feedId(), clock.instant())) {
            throw new CommentNotFoundException();
        }
        feedInteractionPort.adjustCounter(current.feedId(), CounterKind.COMMENT, CounterChange.REMOVED);
    }

    private void validateMember(UUID actorId) {
        if (actorId == null || !commentRepository.isActiveMember(actorId)) {
            throw new AuthenticationRequiredException();
        }
    }

    private Map<UUID, Profile> profilesFor(Set<UUID> authorIds) {
        if (authorIds.isEmpty()) {
            return Map.of();
        }
        PublicProfileReader reader = publicProfileReaders.getIfAvailable();
        if (reader == null) {
            throw new CommentIntegrationUnavailableException();
        }
        Map<UUID, Profile> profiles = reader.readAll(authorIds);
        if (profiles == null || authorIds.stream().anyMatch(id -> invalidProfile(id, profiles.get(id)))) {
            throw new CommentIntegrationUnavailableException();
        }
        return profiles;
    }

    private static boolean invalidProfile(UUID id, @Nullable Profile profile) {
        return profile == null || !id.equals(profile.id()) || profile.nickname() == null
                || profile.nickname().isBlank() || profile.profileImageUrl() == null
                || !profile.profileImageUrl().isAbsolute() || profile.profileImageUrl().getHost() == null
                || !("https".equalsIgnoreCase(profile.profileImageUrl().getScheme())
                || "http".equalsIgnoreCase(profile.profileImageUrl().getScheme()));
    }

    private static CommentResult toResult(CommentSnapshot comment, @Nullable Profile profile, @Nullable UUID viewerId) {
        return new CommentResult(comment.id(), profile, comment.content(), comment.createdAt(),
                profile != null && comment.authorId().equals(viewerId));
    }
}
