package com.harudle.generation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GenerationUsageTest {

    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 8, 6);

    @Test
    @DisplayName("사용 횟수와 제한 횟수로 남은 생성 횟수를 계산한다")
    void calculateRemainingCount() {
        GenerationUsage usage = new GenerationUsage(USAGE_DATE, 2, 3);

        assertThat(usage.remainingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("사용 기록이 없으면 기본 제한 횟수가 남은 빈 사용량을 생성한다")
    void createEmptyUsage() {
        GenerationUsage usage = GenerationUsage.empty(USAGE_DATE);

        assertThat(usage.usedCount()).isZero();
        assertThat(usage.limitCount()).isEqualTo(3);
        assertThat(usage.remainingCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("사용 횟수는 제한 횟수를 초과할 수 없다")
    void rejectUsedCountOverLimit() {
        assertThatThrownBy(() -> new GenerationUsage(USAGE_DATE, 4, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("사용 횟수는 제한 횟수를 초과할 수 없습니다.");
    }
}
