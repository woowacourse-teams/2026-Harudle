package com.harudle.diary.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CurrentDiaryStreakTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 12);

    @Test
    @DisplayName("오늘부터 연속된 성공 날짜만 현재 streak로 계산한다")
    void calculateFromTodayUntilFirstGap() {
        CurrentDiaryStreak streak = CurrentDiaryStreak.calculate(
                TODAY,
                List.of(
                        TODAY.minusDays(4),
                        TODAY.minusDays(2),
                        TODAY,
                        TODAY.minusDays(1)
                )
        );

        assertThat(streak.count()).isEqualTo(3);
        assertThat(streak.recordedToday()).isTrue();
        assertThat(streak.newestDate()).isEqualTo(TODAY);
        assertThat(streak.oldestDate()).isEqualTo(TODAY.minusDays(2));
        assertThat(streak.dates()).containsExactly(
                TODAY,
                TODAY.minusDays(1),
                TODAY.minusDays(2)
        );
    }

    @Test
    @DisplayName("오늘 기록이 없으면 어제까지 이어진 streak를 유지한다")
    void calculateFromYesterdayWhenTodayIsNotRecorded() {
        CurrentDiaryStreak streak = CurrentDiaryStreak.calculate(
                TODAY,
                List.of(
                        TODAY.minusDays(1),
                        TODAY.minusDays(2),
                        TODAY.minusDays(3)
                )
        );

        assertThat(streak.count()).isEqualTo(3);
        assertThat(streak.recordedToday()).isFalse();
        assertThat(streak.dates()).containsExactly(
                TODAY.minusDays(1),
                TODAY.minusDays(2),
                TODAY.minusDays(3)
        );
    }

    @Test
    @DisplayName("오늘과 어제 모두 기록이 없으면 현재 streak는 비어 있다")
    void returnEmptyWhenCurrentStreakExpired() {
        CurrentDiaryStreak streak = CurrentDiaryStreak.calculate(
                TODAY,
                List.of(TODAY.minusDays(2), TODAY.minusDays(3))
        );

        assertThat(streak.count()).isZero();
        assertThat(streak.recordedToday()).isFalse();
        assertThat(streak.dates()).isEmpty();
        assertThatThrownBy(streak::newestDate)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(streak::oldestDate)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("같은 날짜의 여러 일기는 하루로 계산하고 미래 날짜는 제외한다")
    void deduplicateSameDateAndIgnoreFutureDate() {
        CurrentDiaryStreak streak = CurrentDiaryStreak.calculate(
                TODAY,
                List.of(
                        TODAY.plusDays(1),
                        TODAY,
                        TODAY,
                        TODAY.minusDays(1),
                        TODAY.minusDays(1)
                )
        );

        assertThat(streak.count()).isEqualTo(2);
        assertThat(streak.dates()).containsExactly(TODAY, TODAY.minusDays(1));
    }

    @Test
    @DisplayName("계산에 필요한 날짜 인자가 없으면 거부한다")
    void rejectMissingDateArguments() {
        assertThatThrownBy(() -> CurrentDiaryStreak.calculate(null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CurrentDiaryStreak.calculate(TODAY, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CurrentDiaryStreak.calculate(
                TODAY,
                Arrays.asList(TODAY, null)
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
