package com.harudle.diary.service.dto;

import com.harudle.diary.domain.Diary;
import java.time.LocalDate;
import java.util.UUID;

public record CreateDiaryCommand(
        UUID userId,
        LocalDate diaryDate,
        String sourceText,
        UUID idempotencyKey
) {

    public CreateDiaryCommand {
        validateUserId(userId);
        validateDiaryDate(diaryDate);
        sourceText = Diary.normalizeSourceText(sourceText);
        validateIdempotencyKey(idempotencyKey);
    }

    private static void validateUserId(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("사용자 ID는 필수입니다.");
        }
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
