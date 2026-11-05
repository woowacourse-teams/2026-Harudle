package com.harudle.diary.service;

import com.harudle.auth.application.InvalidCurrentUserException;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.diary.service.dto.GuestDiaryClaimResult;
import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.guest.application.GuestSessionResolver;
import com.harudle.guest.application.exception.GuestDiaryAlreadyClaimedException;
import com.harudle.guest.application.exception.GuestDiaryClaimForbiddenException;
import com.harudle.guest.application.exception.GuestDiaryClaimLimitExceededException;
import com.harudle.guest.application.exception.GuestDiaryClaimNotFoundException;
import com.harudle.guest.application.exception.GuestSessionExpiredException;
import com.harudle.guest.application.exception.GuestSessionRequiredException;
import com.harudle.guest.domain.GuestSession;
import com.harudle.guest.repository.GuestSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuestDiaryClaimService {

    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";
    private static final String CLAIMED_BY_USER_CONSTRAINT = "uq_guest_sessions_claimed_by_user";

    private final UserRepository userRepository;
    private final GuestSessionResolver guestSessionResolver;
    private final GuestSessionRepository guestSessionRepository;
    private final DiaryRepository diaryRepository;
    private final DiaryGenerationRepository diaryGenerationRepository;
    private final Clock clock;

    GuestDiaryClaimService(
            UserRepository userRepository,
            GuestSessionResolver guestSessionResolver,
            GuestSessionRepository guestSessionRepository,
            DiaryRepository diaryRepository,
            DiaryGenerationRepository diaryGenerationRepository,
            @Qualifier("authClock") Clock authClock
    ) {
        this.userRepository = userRepository;
        this.guestSessionResolver = guestSessionResolver;
        this.guestSessionRepository = guestSessionRepository;
        this.diaryRepository = diaryRepository;
        this.diaryGenerationRepository = diaryGenerationRepository;
        this.clock = authClock;
    }

    @Transactional
    public GuestDiaryClaimResult claim(UUID memberId, String rawSessionToken) {
        Objects.requireNonNull(memberId, "회원 ID는 필수입니다.");
        // 탈퇴 처리와 같은 회원 행을 먼저 잠가, 탈퇴 중인 회원에게 일기를 옮기지 않는다.
        userRepository.findByIdForUpdate(memberId).orElseThrow(InvalidCurrentUserException::new);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        GuestSession session = resolveSession(rawSessionToken, now);

        if (memberId.equals(session.getGuestUserId())) {
            throw new GuestDiaryClaimForbiddenException();
        }
        if (session.isClaimedBy(memberId)) {
            return toResult(lockClaimTarget(session, memberId), session.getClaimedAt());
        }
        if (session.isClaimed()) {
            throw new GuestDiaryAlreadyClaimedException();
        }
        if (guestSessionRepository.existsByClaimedByUserId(memberId)) {
            throw new GuestDiaryClaimLimitExceededException();
        }
        return transfer(session, memberId, now);
    }

    private GuestSession resolveSession(String rawSessionToken, Instant now) {
        try {
            return guestSessionResolver.resolveForUpdate(rawSessionToken, now);
        } catch (GuestSessionRequiredException | GuestSessionExpiredException exception) {
            throw new GuestDiaryClaimNotFoundException();
        }
    }

    private GuestDiaryClaimResult transfer(GuestSession session, UUID memberId, Instant now) {
        ClaimTarget target = lockClaimTarget(session, session.getGuestUserId());
        target.diary().transferOwnership(session.getGuestUserId(), memberId);
        session.claim(memberId, now);
        flushClaim();
        return toResult(target, now);
    }

    // 생성 완료 처리와 같은 순서(생성 기록 → 일기)로 잠가 교착 상태를 피한다.
    private ClaimTarget lockClaimTarget(GuestSession session, UUID ownerId) {
        UUID diaryId = session.getDiaryId();
        if (diaryId == null) {
            throw new GuestDiaryClaimNotFoundException();
        }
        DiaryGeneration generation = diaryGenerationRepository.findByDiaryIdForUpdate(diaryId)
                .filter(found -> found.getStatus() != GenerationStatus.FAILED)
                .orElseThrow(GuestDiaryClaimNotFoundException::new);
        Diary diary = diaryRepository.findByIdIncludingDeletedForUpdate(diaryId)
                .filter(found -> !found.isDeleted() && found.isOwnedBy(ownerId))
                .orElseThrow(GuestDiaryClaimNotFoundException::new);
        return new ClaimTarget(diary, generation);
    }

    private static GuestDiaryClaimResult toResult(ClaimTarget target, Instant claimedAt) {
        Diary diary = target.diary();
        return new GuestDiaryClaimResult(
                diary.getId(),
                diary.getDiaryDate(),
                target.generation().getStatus(),
                claimedAt
        );
    }

    private void flushClaim() {
        try {
            guestSessionRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && UNIQUE_VIOLATION_SQL_STATE.equals(violation.getSQLState())
                        && CLAIMED_BY_USER_CONSTRAINT.equals(violation.getConstraintName())) {
                    throw new GuestDiaryClaimLimitExceededException(exception);
                }
            }
            throw exception;
        }
    }

    private record ClaimTarget(Diary diary, DiaryGeneration generation) {
    }
}
