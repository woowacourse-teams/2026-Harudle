package com.harudle.generation.diary.service.port;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** 한 응답의 저장소 조회들이 공유하는 경과 시간 예산. 벽시계 변경에 영향을 받지 않는다. */
public final class ImageLookupBudget {

    private final long timeoutNanos;
    private final LongSupplier nanoTime;
    private final long startedAt;

    public ImageLookupBudget(Duration timeout, LongSupplier nanoTime) {
        timeoutNanos = Objects.requireNonNull(timeout).toNanos();
        if (timeoutNanos <= 0) {
            throw new IllegalArgumentException("이미지 조회 시간 예산은 양수여야 합니다.");
        }
        this.nanoTime = Objects.requireNonNull(nanoTime);
        startedAt = nanoTime.getAsLong();
    }

    public static ImageLookupBudget unlimited() {
        return new ImageLookupBudget(Duration.ofNanos(Long.MAX_VALUE), () -> 0);
    }

    public boolean isExhausted() {
        return remainingNanos() < 1_000_000;
    }

    /** SDK에 0을 전달하지 않는다. 예산 소진 시 요청 자체를 시작하지 않는다. */
    public Duration requestTimeout(Duration maximum) {
        long remaining = remainingNanos();
        // SDK 타이머는 밀리초 단위다. 1ms 미만을 0ms(제한 없음)로 전달하지 않는다.
        long timeoutMillis = Math.min(remaining, maximum.toNanos()) / 1_000_000;
        if (timeoutMillis == 0) {
            throw new ImageLookupBudgetExceededException();
        }
        return Duration.ofMillis(timeoutMillis);
    }

    private long remainingNanos() {
        long elapsed = nanoTime.getAsLong() - startedAt;
        return elapsed >= timeoutNanos ? 0 : timeoutNanos - elapsed;
    }
}
