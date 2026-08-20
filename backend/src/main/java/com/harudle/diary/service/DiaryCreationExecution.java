package com.harudle.diary.service;

import com.harudle.diary.service.dto.DiaryGenerationResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

record DiaryCreationExecution(
        UUID diaryId,
        LocalDate diaryDate,
        String sourceText,
        Instant createdAt,
        DiaryGenerationResult generation,
        boolean newlyCreated
) {
}
