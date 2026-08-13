package com.harudle.diary.service.dto;

import java.time.LocalDate;
import java.util.List;

public record DiaryDayResult(
        LocalDate date,
        List<DiarySummaryResult> items
) {

    public DiaryDayResult {
        items = List.copyOf(items);
    }

    public boolean hasItems() {
        return !items.isEmpty();
    }
}
