package com.harudle.moderation.service;

import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.moderation.domain.ModerationChange;
import com.harudle.moderation.domain.ModerationState;
import com.harudle.moderation.domain.ModerationTargetType;
import com.harudle.moderation.repository.ModerationRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminModerationService {
    private final ModerationRepository repository;
    private final Clock clock;

    public AdminModerationService(ModerationRepository repository, @Qualifier("serviceClock") Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ModerationState find(UUID actorId, ModerationTargetType type, UUID id) {
        requireAdmin(actorId);
        requireTarget(type, id);
        return repository.find(type, id).orElseGet(() -> ModerationState.initial(type, id));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ModerationState change(UUID actorId, ModerationTargetType type, UUID id, ModerationChange change) {
        requireAdmin(actorId);
        Objects.requireNonNull(type);
        Objects.requireNonNull(id);
        Objects.requireNonNull(change);
        if (!repository.lockManageableTarget(type, id)) throw new ModerationTargetNotFoundException();
        ModerationState current = repository.lockOrCreate(type, id);
        if (current.isReplay(change, actorId)) return current;
        if (current.version() != change.expectedVersion()) throw new ModerationConflictException();
        if (current.restricted() == change.restricted()) return current;
        ModerationState next = current.changed(change, actorId, clock.instant().truncatedTo(ChronoUnit.MICROS));
        repository.saveChange(next);
        return next;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ModerationHistoryPage history(UUID actorId, ModerationTargetType type, UUID id, int page, int size) {
        requireAdmin(actorId);
        requireTarget(type, id);
        if (size < 1 || size > 100 || page < 0 || page > Integer.MAX_VALUE / 100) {
            throw new IllegalArgumentException("조회 범위가 올바르지 않습니다.");
        }
        long total = repository.countHistory(type, id);
        return new ModerationHistoryPage(repository.history(type, id, page * size, size), page, size,
                total, (total + size - 1) / size, (long) (page + 1) * size < total);
    }

    private void requireAdmin(UUID actorId) {
        if (actorId == null) throw new AuthenticationRequiredException();
        if (!repository.isActiveAdmin(actorId)) throw new ModerationAccessDeniedException();
    }

    private void requireTarget(ModerationTargetType type, UUID id) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(id);
        if (!repository.targetExists(type, id) && repository.find(type, id).isEmpty()) {
            throw new ModerationTargetNotFoundException();
        }
    }
}
