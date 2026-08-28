package com.harudle.diary.service.dto;

import java.time.LocalDate;
import java.util.List;

public record DiaryStreakDayResult(
        LocalDate date,
        List<DiarySummaryResult> items
) {

    public DiaryStreakDayResult {
        items = List.copyOf(items);
    }

}
