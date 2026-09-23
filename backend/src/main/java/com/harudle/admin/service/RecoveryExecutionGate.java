package com.harudle.admin.service;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public final class RecoveryExecutionGate {
    private final ReentrantLock lock = new ReentrantLock(true);
    private final long intervalNanos;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private boolean completed;
    private long completedAt;

    @Autowired
    public RecoveryExecutionGate(@Value("${harudle.admin.image-recovery.interval:10s}") Duration interval) {
        this(interval, System::nanoTime, Thread::sleep);
    }

    RecoveryExecutionGate(Duration interval, LongSupplier nanoTime, Sleeper sleeper) {
        if (interval.isNegative()) {
            throw new IllegalArgumentException("복구 작업 간격은 음수일 수 없습니다.");
        }
        this.intervalNanos = interval.toNanos();
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    public <T> T execute(Supplier<T> operation) {
        try {
            lock.lockInterruptibly();
            try {
                awaitInterval();
                try {
                    return operation.get();
                } finally {
                    // 실패한 요청도 공급자에 부하를 줄 수 있으므로 동일하게 간격을 둔다.
                    completedAt = nanoTime.getAsLong();
                    completed = true;
                }
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "복구 작업 대기가 중단되었습니다.", exception);
        }
    }

    private void awaitInterval() throws InterruptedException {
        while (completed) {
            long remaining = intervalNanos - (nanoTime.getAsLong() - completedAt);
            if (remaining <= 0) {
                return;
            }
            sleeper.sleep(Duration.ofNanos(remaining));
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
