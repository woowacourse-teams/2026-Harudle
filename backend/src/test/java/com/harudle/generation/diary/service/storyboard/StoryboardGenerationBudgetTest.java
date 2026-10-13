package com.harudle.generation.diary.service.storyboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StoryboardGenerationBudgetTest {

    private final AtomicLong clock = new AtomicLong();

    @Test
    @DisplayName("전체 예산이 충분하면 Provider별 제한 시간을 적용한다")
    void capAttemptAtProviderTimeout() {
        var budget = StoryboardGenerationBudget.start(Duration.ofSeconds(120), clock::get);

        assertThat(budget.newAttempt(Duration.ofSeconds(60)).remainingTime()).isEqualTo(Duration.ofSeconds(60));
        assertThat(budget.remainingTime()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    @DisplayName("보조 호출의 제한 시간은 전체 예산의 남은 시간을 넘지 않는다")
    void capFallbackAtRemainingBudget() {
        var budget = StoryboardGenerationBudget.start(Duration.ofSeconds(120), clock::get);
        clock.addAndGet(Duration.ofSeconds(80).toNanos());

        assertThat(budget.newAttempt(Duration.ofSeconds(60)).remainingTime()).isEqualTo(Duration.ofSeconds(40));
        assertThat(budget.remainingTime()).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    @DisplayName("빠른 실패 후 새 호출을 만들어도 전체 예산을 다시 시작하지 않는다")
    void preserveBudgetAcrossAttempts() {
        var budget = StoryboardGenerationBudget.start(Duration.ofSeconds(120), clock::get);
        var primary = budget.newAttempt(Duration.ofSeconds(60));
        clock.addAndGet(Duration.ofSeconds(5).toNanos());
        primary.complete();
        var fallback = budget.newAttempt(Duration.ofSeconds(60));

        assertThat(fallback.remainingTime()).isEqualTo(Duration.ofSeconds(60));
        assertThat(budget.remainingTime()).isEqualTo(Duration.ofSeconds(115));
    }

    @Test
    @DisplayName("전체 예산이 소진되면 새 호출의 남은 시간은 0이다")
    void exhaustBudget() {
        var budget = StoryboardGenerationBudget.start(Duration.ofSeconds(120), clock::get);
        clock.addAndGet(Duration.ofSeconds(121).toNanos());

        assertThat(budget.remainingTime()).isZero();
        assertThat(budget.newAttempt(Duration.ofSeconds(60)).remainingTime()).isZero();
    }

    @Test
    @DisplayName("단조 시계 값이 오버플로해도 경과 시간으로 예산을 계산한다")
    void handleMonotonicClockWraparound() {
        clock.set(Long.MAX_VALUE - Duration.ofSeconds(10).toNanos());
        var budget = StoryboardGenerationBudget.start(Duration.ofSeconds(120), clock::get);
        var primary = budget.newAttempt(Duration.ofSeconds(60));
        clock.addAndGet(Duration.ofSeconds(20).toNanos());

        assertThat(budget.remainingTime()).isEqualTo(Duration.ofSeconds(100));
        assertThat(primary.remainingTime()).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    @DisplayName("양수가 아니거나 나노초로 표현할 수 없는 제한 시간을 거절한다")
    void rejectInvalidTimeout() {
        assertThatThrownBy(() -> StoryboardGenerationBudget.start(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StoryboardGenerationBudget.start(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StoryboardGenerationBudget.start(Duration.ofSeconds(Long.MAX_VALUE)))
                .isInstanceOf(IllegalArgumentException.class);
        var budget = StoryboardGenerationBudget.start(Duration.ofSeconds(120), clock::get);
        assertThatThrownBy(() -> budget.newAttempt(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
