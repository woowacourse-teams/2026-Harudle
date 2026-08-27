package com.harudle.diary.service.dto;

import java.util.List;

public record DiaryStreakResult(
        boolean recordedToday,
        List<DiaryStreakDayResult> days
) {

    public DiaryStreakResult {
        days = List.copyOf(days);
    }

    public int streakCount() {
        return days.size();
    }

}
