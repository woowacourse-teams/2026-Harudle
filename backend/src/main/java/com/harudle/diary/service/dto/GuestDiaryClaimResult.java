package com.harudle.diary.service.dto;

import com.harudle.generation.diary.domain.GenerationStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record GuestDiaryClaimResult(
        UUID diaryId,
        LocalDate diaryDate,
        GenerationStatus generationStatus,
        Instant claimedAt
) {
}
