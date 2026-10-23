package com.harudle.generation.diary.service;

import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationQueryRepository;
import com.harudle.generation.diary.repository.DiaryGenerationStatusSnapshot;
import com.harudle.generation.diary.service.dto.DiaryGenerationStatusResult;
import com.harudle.generation.diary.service.exception.DiaryGenerationNotFoundException;
import com.harudle.guest.application.GuestSessionResolver;
import com.harudle.guest.domain.GuestSession;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DiaryGenerationStatusQueryService {

    private final DiaryGenerationQueryRepository generationRepository;
    private final GuestSessionResolver guestSessionResolver;
    private final Clock clock;

    DiaryGenerationStatusQueryService(
            DiaryGenerationQueryRepository generationRepository,
            GuestSessionResolver guestSessionResolver,
            @Qualifier("authClock") Clock clock
    ) {
        this.generationRepository = generationRepository;
        this.guestSessionResolver = guestSessionResolver;
        this.clock = clock;
    }

    public DiaryGenerationStatusResult getStatus(UUID userId, UUID generationId) {
        return toResult(findOwnedGeneration(userId, generationId));
    }

    public DiaryGenerationStatusResult getGuestStatus(String rawSessionToken, UUID generationId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        GuestSession session = guestSessionResolver.resolve(rawSessionToken, now);
        if (!session.isUsed()) {
            throw new DiaryGenerationNotFoundException();
        }
        DiaryGenerationStatusSnapshot generation = findOwnedGeneration(session.getGuestUserId(), generationId);
        if (!session.isUsedForDiary(generation.diaryId())) {
            throw new DiaryGenerationNotFoundException();
        }
        return toResult(generation);
    }

    private DiaryGenerationStatusSnapshot findOwnedGeneration(UUID userId, UUID generationId) {
        return generationRepository.findStatusSnapshotByIdAndUserId(generationId, userId)
                .orElseThrow(DiaryGenerationNotFoundException::new);
    }

    private DiaryGenerationStatusResult toResult(DiaryGenerationStatusSnapshot generation) {
        return new DiaryGenerationStatusResult(
                generation.id(),
                generation.status(),
                generation.status() == GenerationStatus.SUCCEEDED ? generation.diaryId() : null,
                generation.status() == GenerationStatus.FAILED ? generation.errorCode() : null
        );
    }
}
