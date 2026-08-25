package com.harudle.diary.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CurrentDiaryStreak {

    private final LocalDate today;
    private final List<LocalDate> dates;

    public CurrentDiaryStreak(LocalDate today, List<LocalDate> dates) {
        this.today = today;
        this.dates = dates;
    }

    public static CurrentDiaryStreak calculate(LocalDate today, List<LocalDate> successfulDiaryDates) {
        validateToday(today);
        validateSuccessfulDiaryDates(successfulDiaryDates);

        Set<LocalDate> activityDates = createActivityDates(today, successfulDiaryDates);
        LocalDate anchorDate = findAnchorDate(today, activityDates);

        if (!activityDates.contains(anchorDate)) {
            return new CurrentDiaryStreak(today, List.of());
        }

        List<LocalDate> streakDates = collectConsecutiveDates(anchorDate, activityDates);
        return new CurrentDiaryStreak(today, streakDates);
    }

    private static Set<LocalDate> createActivityDates(LocalDate today, List<LocalDate> successfulDiaryDates) {
        Set<LocalDate> activityDates = new HashSet<>();

        for (LocalDate date : successfulDiaryDates) {
            validateActivityDate(date);

            if (!date.isAfter(today)) {
                activityDates.add(date);
            }
        }

        return activityDates;
    }

    private static LocalDate findAnchorDate(LocalDate today, Set<LocalDate> activityDates) {
        if (activityDates.contains(today)) {
            return today;
        }

        return today.minusDays(1);
    }

    private static List<LocalDate> collectConsecutiveDates(LocalDate anchorDate, Set<LocalDate> activityDates) {
        List<LocalDate> streakDates = new ArrayList<>();

        LocalDate currentDate = anchorDate;
        while (activityDates.contains(currentDate)) {
            streakDates.add(currentDate);
            currentDate = currentDate.minusDays(1);
        }

        return streakDates;
    }

    private void validateNotEmpty() {
        if (isEmpty()) {
            throw new IllegalStateException("빈 streak에는 시작일과 종료일이 없습니다.");
        }
    }

    private static void validateToday(LocalDate today) {
        if (today == null) {
            throw new IllegalArgumentException("오늘 날짜는 필수입니다.");
        }
    }

    private static void validateSuccessfulDiaryDates(List<LocalDate> successfulDiaryDates) {
        if (successfulDiaryDates == null) {
            throw new IllegalArgumentException("성공한 일기 날짜 목록은 필수입니다.");
        }
    }

    private static void validateActivityDate(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("일기 날짜는 null일 수 없습니다.");
        }
    }

    public int count() {
        return dates.size();
    }

    public boolean recordedToday() {
        return !dates.isEmpty() && dates.getFirst().equals(today);
    }

    public boolean isEmpty() {
        return dates.isEmpty();
    }

    public LocalDate newestDate() {
        validateNotEmpty();
        return dates.getFirst();
    }

    public LocalDate oldestDate() {
        validateNotEmpty();
        return dates.getLast();
    }

    public List<LocalDate> dates() {
        return dates;
    }
}
