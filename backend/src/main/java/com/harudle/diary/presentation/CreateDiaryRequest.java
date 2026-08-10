package com.harudle.diary.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record CreateDiaryRequest(
        @NotNull(message = "일기 날짜는 필수입니다.")
        LocalDate diaryDate,

        @NotBlank(message = "일기 내용은 필수입니다.")
        String sourceText
) {
}
