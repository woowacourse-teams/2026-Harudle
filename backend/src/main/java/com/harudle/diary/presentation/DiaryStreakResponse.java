package com.harudle.diary.presentation;

import java.util.List;

public record DiaryStreakResponse(
        int streakCount,
        boolean recordedToday,
        List<DiaryStreakDayResponse> days
) {

    public DiaryStreakResponse {
        days = List.copyOf(days);
    }

}
