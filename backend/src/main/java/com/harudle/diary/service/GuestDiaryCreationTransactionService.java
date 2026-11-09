package com.harudle.diary.service;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateGuestDiaryCommand;
import com.harudle.guest.application.GuestSessionResolver;
import com.harudle.guest.application.exception.GuestTrialAlreadyUsedException;
import com.harudle.guest.domain.GuestSession;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class GuestDiaryCreationTransactionService {

    private final DiaryCreationClaimService claimService;
    private final GuestSessionResolver guestSessionResolver;
    private final Clock clock;

    GuestDiaryCreationTransactionService(
            DiaryCreationClaimService claimService,
            GuestSessionResolver guestSessionResolver,
            @Qualifier("authClock") Clock authClock
    ) {
        this.claimService = claimService;
        this.guestSessionResolver = guestSessionResolver;
        this.clock = authClock;
    }

    @Transactional
    GuestDiaryCreationClaim claim(
            String rawSessionToken,
            CreateGuestDiaryCommand guestCommand,
            boolean generationAvailable
    ) {
        return claim(rawSessionToken, guestCommand, generationAvailable, false);
    }

    @Transactional
    GuestDiaryCreationClaim enqueue(
            String rawSessionToken,
            CreateGuestDiaryCommand guestCommand,
            boolean generationAvailable
    ) {
        return claim(rawSessionToken, guestCommand, generationAvailable, true);
    }

    private GuestDiaryCreationClaim claim(
            String rawSessionToken,
            CreateGuestDiaryCommand guestCommand,
            boolean generationAvailable,
            boolean queueForWorker
    ) {
        Instant now = currentTime();
        GuestSession session = resolveUnclaimedSession(rawSessionToken, now);
        CreateDiaryCommand command = guestCommand.toDiaryCommand(session.getGuestUserId());

        if (session.isUsed()) {
            return findReplayClaim(session, command);
        }

        DiaryCreationClaim claim = queueForWorker
                ? claimService.enqueue(command, generationAvailable)
                : claimService.claim(command, generationAvailable);
        session.useForDiary(claim.diaryId(), now);
        return new GuestDiaryCreationClaim(command, claim);
    }

    @Transactional
    Optional<GuestDiaryCreationClaim> findExistingClaim(
            String rawSessionToken,
            CreateGuestDiaryCommand guestCommand
    ) {
        Instant now = currentTime();
        GuestSession session = resolveUnclaimedSession(rawSessionToken, now);
        CreateDiaryCommand command = guestCommand.toDiaryCommand(session.getGuestUserId());

        return claimService.findExistingClaim(command)
                .map(claim -> recoverSessionAssociation(session, command, claim, now));
    }

    // 회원에게 옮긴 일기는 게스트 세션으로 다시 받을 수 없게 한다.
    // 새 세션 발급으로 체험을 다시 하지 않도록 세션 오류(401)가 아닌 체험 사용 완료(409)로 거절한다.
    private GuestSession resolveUnclaimedSession(String rawSessionToken, Instant now) {
        GuestSession session = guestSessionResolver.resolveForUpdate(rawSessionToken, now);
        if (session.isClaimed()) {
            throw new GuestTrialAlreadyUsedException();
        }
        return session;
    }

    private GuestDiaryCreationClaim findReplayClaim(
            GuestSession session,
            CreateDiaryCommand command
    ) {
        DiaryCreationClaim claim = claimService.findExistingClaim(command)
                .filter(existingClaim -> session.isUsedForDiary(existingClaim.diaryId()))
                .orElseThrow(GuestTrialAlreadyUsedException::new);
        return new GuestDiaryCreationClaim(command, claim);
    }

    private GuestDiaryCreationClaim recoverSessionAssociation(
            GuestSession session,
            CreateDiaryCommand command,
            DiaryCreationClaim claim,
            Instant now
    ) {
        if (session.isUsed()) {
            if (!session.isUsedForDiary(claim.diaryId())) {
                throw new GuestTrialAlreadyUsedException();
            }
            return new GuestDiaryCreationClaim(command, claim);
        }

        session.useForDiary(claim.diaryId(), now);
        return new GuestDiaryCreationClaim(command, claim);
    }

    private Instant currentTime() {
        return Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
