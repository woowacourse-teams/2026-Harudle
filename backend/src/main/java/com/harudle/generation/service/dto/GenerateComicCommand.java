package com.harudle.generation.service.dto;

import com.harudle.common.validation.TextValidator;
import java.time.LocalDate;
import java.util.UUID;

public record GenerateComicCommand(
        UUID userId,
        UUID diaryId,
        LocalDate diaryDate,
        String diaryText,
        UUID idempotencyKey
) {

    public GenerateComicCommand {
        validateUserId(userId);
        validateDiaryId(diaryId);
        validateDiaryDate(diaryDate);
        diaryText = TextValidator.normalizeRequired(diaryText, "일기 내용은 필수입니다.");
        validateIdempotencyKey(idempotencyKey);
    }

    private static void validateUserId(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("사용자 ID는 필수입니다.");
        }
    }

    private static void validateDiaryId(UUID diaryId) {
        if (diaryId == null) {
            throw new IllegalArgumentException("일기 ID는 필수입니다.");
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
