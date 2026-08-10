package com.harudle.diary.presentation;

import com.harudle.common.validation.CodePointLength;
import com.harudle.diary.domain.Diary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record CreateDiaryRequest(
        @NotNull(message = "일기 날짜는 필수입니다.")
        LocalDate diaryDate,

        @NotBlank(message = "일기 내용은 필수입니다.")
        @CodePointLength(
                max = Diary.MAX_SOURCE_TEXT_CODE_POINTS,
                message = "일기 내용은 {max}자 이하여야 합니다."
        )
        String sourceText
) {
}
