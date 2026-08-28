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
        Instant now = currentTime();
        GuestSession session = guestSessionResolver.resolveForUpdate(rawSessionToken, now);
        CreateDiaryCommand command = guestCommand.toDiaryCommand(session.getGuestUserId());

        if (session.isUsed()) {
            return findReplayClaim(session, command);
        }

        DiaryCreationClaim claim = claimService.claim(command, generationAvailable);
        session.useForDiary(claim.diaryId(), now);
        return new GuestDiaryCreationClaim(command, claim);
    }

    @Transactional
    Optional<GuestDiaryCreationClaim> findExistingClaim(
            String rawSessionToken,
            CreateGuestDiaryCommand guestCommand
    ) {
        Instant now = currentTime();
        GuestSession session = guestSessionResolver.resolveForUpdate(rawSessionToken, now);
        CreateDiaryCommand command = guestCommand.toDiaryCommand(session.getGuestUserId());

        return claimService.findExistingClaim(command)
                .map(claim -> recoverSessionAssociation(session, command, claim, now));
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
