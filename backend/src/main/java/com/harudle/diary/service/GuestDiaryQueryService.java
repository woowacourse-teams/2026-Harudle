package com.harudle.diary.service;

import com.harudle.diary.service.dto.DiaryDetailResult;
import com.harudle.diary.service.exception.DiaryNotFoundException;
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
public class GuestDiaryQueryService {

    private final GuestSessionResolver guestSessionResolver;
    private final DiaryQueryService diaryQueryService;
    private final Clock clock;

    GuestDiaryQueryService(
            GuestSessionResolver guestSessionResolver,
            DiaryQueryService diaryQueryService,
            @Qualifier("authClock") Clock authClock
    ) {
        this.guestSessionResolver = guestSessionResolver;
        this.diaryQueryService = diaryQueryService;
        this.clock = authClock;
    }

    public DiaryDetailResult getDetail(
            String rawSessionToken,
            UUID diaryId
    ) {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        GuestSession session = guestSessionResolver.resolve(rawSessionToken, now);
        if (!session.isUsedForDiary(diaryId)) {
            throw new DiaryNotFoundException();
        }
        return diaryQueryService.getDetail(session.getGuestUserId(), diaryId);
    }
}
