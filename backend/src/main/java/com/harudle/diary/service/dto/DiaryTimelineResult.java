package com.harudle.diary.service.dto;

import java.util.List;

public record DiaryTimelineResult(
        int year,
        int month,
        List<DiaryDayResult> days
) {

    public DiaryTimelineResult {
        days = List.copyOf(days);
    }
}
