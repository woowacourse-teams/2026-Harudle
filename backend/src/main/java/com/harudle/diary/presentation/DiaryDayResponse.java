package com.harudle.diary.presentation;

import java.time.LocalDate;
import java.util.List;

public record DiaryDayResponse(
        LocalDate date,
        boolean exist,
        List<DiarySummaryResponse> items
) {

    public DiaryDayResponse {
        items = List.copyOf(items);
    }
}
