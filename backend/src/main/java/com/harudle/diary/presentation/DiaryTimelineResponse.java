package com.harudle.diary.presentation;

import java.util.List;

public record DiaryTimelineResponse(
        int year,
        int month,
        List<DiaryDayResponse> days
) {

    public DiaryTimelineResponse {
        days = List.copyOf(days);
    }
}
