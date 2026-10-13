package com.harudle.generation.diary.service.storyboard;

import com.harudle.generation.diary.service.port.StoryboardAttemptContext;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** One monotonic time budget shared by primary and fallback attempts. */
public final class StoryboardGenerationBudget {

    private final long startedAt;
    private final long timeoutNanos;
    private final LongSupplier nanoTime;

    private StoryboardGenerationBudget(Duration timeout, LongSupplier nanoTime) {
        this.timeoutNanos = positiveNanos(timeout);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.startedAt = nanoTime.getAsLong();
    }

    public static StoryboardGenerationBudget start(Duration timeout) {
        return start(timeout, System::nanoTime);
    }

    static StoryboardGenerationBudget start(Duration timeout, LongSupplier nanoTime) {
        return new StoryboardGenerationBudget(timeout, nanoTime);
    }

    public Duration remainingTime() {
        return Duration.ofNanos(remainingNanos(nanoTime.getAsLong()));
    }

    StoryboardAttemptContext newAttempt(Duration timeout) {
        long limitNanos = positiveNanos(timeout);
        long now = nanoTime.getAsLong();
        long attemptNanos = Math.min(limitNanos, remainingNanos(now));
        return new StoryboardAttemptContext(now + attemptNanos, nanoTime);
    }

    private long remainingNanos(long now) {
        return Math.max(0, timeoutNanos - (now - startedAt));
    }

    private static long positiveNanos(Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("스토리보드 제한 시간은 양수여야 합니다.");
        }
        try {
            return timeout.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("스토리보드 제한 시간이 너무 큽니다.", exception);
        }
    }
}
