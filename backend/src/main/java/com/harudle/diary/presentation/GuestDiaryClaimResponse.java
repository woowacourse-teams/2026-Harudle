package com.harudle.diary.presentation;

import com.harudle.diary.service.dto.GuestDiaryClaimResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

public record GuestDiaryClaimResponse(
        UUID diaryId,
        @Schema(description = "일기 날짜 (YYYY-MM-DD)")
        LocalDate diaryDate,
        @Schema(
                description = "가져온 시점의 생성 상태",
                allowableValues = {"PENDING", "PROCESSING", "SUCCEEDED"}
        )
        String generationStatus,
        @Schema(description = "회원에게 옮긴 시각 (RFC 3339)")
        OffsetDateTime claimedAt
) {

    static GuestDiaryClaimResponse from(GuestDiaryClaimResult result, ZoneId serviceZoneId) {
        return new GuestDiaryClaimResponse(
                result.diaryId(),
                result.diaryDate(),
                result.generationStatus().name(),
                result.claimedAt().atZone(serviceZoneId).toOffsetDateTime()
        );
    }
}
