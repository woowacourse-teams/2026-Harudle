package com.harudle.diary.service.dto;

import com.harudle.diary.domain.Diary;
import java.time.LocalDate;
import java.util.UUID;

public record CreateGuestDiaryCommand(
        LocalDate diaryDate,
        String sourceText,
        UUID idempotencyKey
) {

    public CreateGuestDiaryCommand {
        validateDiaryDate(diaryDate);
        sourceText = Diary.normalizeSourceText(sourceText);
        validateIdempotencyKey(idempotencyKey);
    }

    public CreateDiaryCommand toDiaryCommand(UUID guestUserId) {
        return new CreateDiaryCommand(
                guestUserId,
                diaryDate,
                sourceText,
                idempotencyKey
        );
    }

    private static void validateDiaryDate(LocalDate diaryDate) {
        if (diaryDate == null) {
            throw new IllegalArgumentException("일기 날짜는 필수입니다.");
        }
    }

    private static void validateIdempotencyKey(UUID idempotencyKey) {
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("멱등성 키는 필수입니다.");
        }
    }
}
