package com.harudle.diary.presentation;

import java.time.LocalDate;
import java.util.List;

public record DiaryStreakDayResponse(
        LocalDate date,
        List<DiarySummaryResponse> items
) {

    public DiaryStreakDayResponse {
        items = List.copyOf(items);
    }

}
