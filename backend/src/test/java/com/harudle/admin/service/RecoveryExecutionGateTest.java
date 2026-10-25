package com.harudle.admin.service;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RecoveryExecutionGateTest {
    @Test
    void waitsTenSecondsAfterCompletionNotStart() {
        var time = new AtomicLong();
        List<Duration> sleeps = new ArrayList<>();
        var gate = new RecoveryExecutionGate(Duration.ofSeconds(10), time::get, duration -> {
            sleeps.add(duration);
            time.addAndGet(duration.toNanos());
        });
        gate.execute(() -> { time.addAndGet(Duration.ofMinutes(2).toNanos()); return "first"; });
        assertThat(sleeps).isEmpty();
        assertThat(gate.execute(() -> time.get())).isEqualTo(Duration.ofSeconds(130).toNanos());
        assertThat(sleeps).containsExactly(Duration.ofSeconds(10));
    }

    @Test
    void failureAlsoStartsCooldownAndReleasesLock() {
        var time = new AtomicLong();
        var gate = new RecoveryExecutionGate(Duration.ofSeconds(10), time::get,
                duration -> time.addAndGet(duration.toNanos()));
        assertThatThrownBy(() -> gate.execute(() -> { throw new IllegalStateException("failed"); }))
                .isInstanceOf(IllegalStateException.class);
        gate.execute(() -> "next");
        assertThat(time.get()).isEqualTo(Duration.ofSeconds(10).toNanos());
    }

    @Test
    void alreadyElapsedIntervalDoesNotWaitAgain() {
        var time = new AtomicLong();
        var gate = new RecoveryExecutionGate(Duration.ofSeconds(10), time::get,
                duration -> { throw new AssertionError("unexpected sleep"); });
        gate.execute(() -> "first");
        time.addAndGet(Duration.ofSeconds(15).toNanos());
        assertThat(gate.execute(() -> "next")).isEqualTo("next");
    }

    @Test
    void concurrentOperationsNeverOverlap() throws Exception {
        var gate = new RecoveryExecutionGate(Duration.ZERO);
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                    } catch (InterruptedException e) { throw new RuntimeException(e); }
                    gate.execute(() -> {
                        maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                        try { Thread.sleep(20); } catch (InterruptedException e) { throw new RuntimeException(e); }
                        active.decrementAndGet();
                        return null;
                    });
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        }
        assertThat(maximum.get()).isEqualTo(1);
    }
}
