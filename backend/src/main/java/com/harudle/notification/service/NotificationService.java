package com.harudle.notification.service;

import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.notification.query.NotificationCursor;
import com.harudle.notification.repository.NotificationReadTarget;
import com.harudle.notification.repository.NotificationRepository;
import com.harudle.notification.repository.NotificationSnapshot;
import com.harudle.notification.service.dto.NotificationPageResult;
import com.harudle.notification.service.dto.NotificationResult;
import com.harudle.notification.service.exception.NotificationAccessDeniedException;
import com.harudle.notification.service.exception.NotificationIntegrationUnavailableException;
import com.harudle.notification.service.exception.NotificationNotFoundException;
import com.harudle.profile.service.port.PublicProfileReader;
import com.harudle.profile.service.port.PublicProfileReader.Profile;
import java.time.Clock;
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
public class NotificationService {
    private final NotificationRepository repository;
    private final ObjectProvider<PublicProfileReader> publicProfileReaders;
    private final NotificationCursorCodec cursorCodec;
    private final Clock clock;

    public NotificationService(NotificationRepository repository,
            ObjectProvider<PublicProfileReader> publicProfileReaders, NotificationCursorCodec cursorCodec,
            @Qualifier("serviceClock") Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.publicProfileReaders = Objects.requireNonNull(publicProfileReaders);
        this.cursorCodec = Objects.requireNonNull(cursorCodec);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NotificationPageResult list(UUID recipientId, @Nullable String cursor, int size) {
        validateMember(recipientId);
        if (size < 1 || size > 50) {
            throw new IllegalArgumentException("알림 페이지 크기는 1~50이어야 합니다.");
        }
        List<NotificationSnapshot> found = repository.findAfter(recipientId, cursorCodec.decode(recipientId, cursor), size + 1);
        long unreadCount = repository.countUnread(recipientId);
        boolean hasNext = found.size() > size;
        List<NotificationSnapshot> page = found.stream().limit(size).toList();
        Set<UUID> actorIds = page.stream().map(NotificationSnapshot::actorId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, Profile> profiles = actorIds.isEmpty() ? Map.of() : profilesFor(actorIds);
        List<NotificationResult> items = page.stream().map(notification -> toResult(notification,
                notification.actorId() == null ? null : profiles.get(notification.actorId()))).toList();
        String nextCursor = null;
        if (hasNext) {
            NotificationSnapshot last = page.getLast();
            nextCursor = cursorCodec.encode(new NotificationCursor(recipientId, last.createdAt(), last.id()));
        }
        return new NotificationPageResult(items, nextCursor, hasNext, unreadCount);
    }

    @Transactional
    public void markRead(UUID recipientId, UUID notificationId) {
        validateMember(recipientId);
        Objects.requireNonNull(notificationId, "알림 ID는 필수입니다.");
        NotificationReadTarget target = repository.findVisibleById(notificationId)
                .orElseThrow(NotificationNotFoundException::new);
        if (!recipientId.equals(target.recipientId())) {
            throw new NotificationAccessDeniedException();
        }
        // The repository preserves the first read_at using COALESCE, including repeated requests.
        if (!repository.markRead(notificationId, recipientId, clock.instant())) {
            throw new NotificationNotFoundException();
        }
    }

    private void validateMember(UUID recipientId) {
        if (recipientId == null || !repository.isActiveMember(recipientId)) {
            throw new AuthenticationRequiredException();
        }
    }

    private Map<UUID, Profile> profilesFor(Set<UUID> actorIds) {
        PublicProfileReader reader = publicProfileReaders.getIfAvailable();
        if (reader == null) {
            throw new NotificationIntegrationUnavailableException();
        }
        Map<UUID, Profile> profiles = reader.readAll(actorIds);
        if (profiles == null || actorIds.stream().anyMatch(id -> invalidProfile(id, profiles.get(id)))) {
            throw new NotificationIntegrationUnavailableException();
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

    private static NotificationResult toResult(NotificationSnapshot notification, @Nullable Profile actor) {
        String prefix = actor == null ? "탈퇴한 회원이 " : actor.nickname() + "님이 ";
        String action = switch (notification.type()) {
            case LIKE -> "좋아요를 눌렀습니다.";
            case COMMENT -> "댓글을 남겼습니다.";
        };
        return new NotificationResult(notification.id(), notification.type(), notification.feedId(), actor,
                prefix + action, notification.createdAt(), notification.readAt() != null);
    }
}
