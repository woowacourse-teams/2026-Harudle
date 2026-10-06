package com.harudle.generation.diary.service.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ImageLookupBudgetTest {
    @Test
    void capsEachRequestToRemainingTimeWithoutAdvancingOnRead() {
        AtomicLong time = new AtomicLong();
        ImageLookupBudget budget = new ImageLookupBudget(Duration.ofSeconds(2), time::get);
        assertThat(budget.requestTimeout(Duration.ofSeconds(10))).isEqualTo(Duration.ofSeconds(2));
        assertThat(budget.requestTimeout(Duration.ofMillis(300))).isEqualTo(Duration.ofMillis(300));
        time.addAndGet(Duration.ofMillis(1500).toNanos());
        assertThat(budget.requestTimeout(Duration.ofSeconds(10))).isEqualTo(Duration.ofMillis(500));
        time.addAndGet(Duration.ofMillis(500).toNanos());
        assertThat(budget.isExhausted()).isTrue();
        assertThatThrownBy(() -> budget.requestTimeout(Duration.ofSeconds(10)))
                .isInstanceOf(ImageLookupBudgetExceededException.class);
    }

    @Test
    void subMillisecondRemainderCannotBecomeUnlimitedSdkTimeout() {
        AtomicLong time = new AtomicLong();
        ImageLookupBudget budget = new ImageLookupBudget(Duration.ofMillis(1), time::get);
        time.addAndGet(1);
        assertThat(budget.isExhausted()).isTrue();
        assertThatThrownBy(() -> budget.requestTimeout(Duration.ofSeconds(10)))
                .isInstanceOf(ImageLookupBudgetExceededException.class);
    }

    @Test
    void measuresElapsedTimeAcrossNanoTimeOverflow() {
        AtomicLong time = new AtomicLong(Long.MAX_VALUE - Duration.ofSeconds(1).toNanos());
        ImageLookupBudget budget = new ImageLookupBudget(Duration.ofSeconds(2), time::get);
        time.addAndGet(Duration.ofMillis(1500).toNanos());
        assertThat(budget.requestTimeout(Duration.ofSeconds(10))).isEqualTo(Duration.ofMillis(500));
        time.addAndGet(Duration.ofMillis(500).toNanos());
        assertThat(budget.isExhausted()).isTrue();
    }
}
