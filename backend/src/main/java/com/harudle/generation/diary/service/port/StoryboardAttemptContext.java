package com.harudle.generation.diary.service.port;

import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.exception.AiProviderFailureType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Deadline and cancellation hooks for one provider attempt, without exposing transport types. */
public final class StoryboardAttemptContext implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StoryboardAttemptContext.class);

    private final long deadlineNanos;
    private final LongSupplier nanoTime;
    private final List<Registration> registrations = new ArrayList<>();
    private CancellationReason cancellationReason;
    private boolean closed;

    /** The deadline must use the same monotonic clock as nanoTime, not wall-clock time. */
    public StoryboardAttemptContext(long deadlineNanos, LongSupplier nanoTime) {
        this.deadlineNanos = deadlineNanos;
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    public Duration remainingTime() {
        return Duration.ofNanos(Math.max(0, deadlineNanos - nanoTime.getAsLong()));
    }

    public synchronized Optional<CancellationReason> cancellationReason() {
        return Optional.ofNullable(cancellationReason);
    }

    /** Hooks must promptly cancel only this request. Closing a registration removes its hook. */
    public CancellationRegistration onCancellation(Runnable cancellation) {
        Registration registration = new Registration(Objects.requireNonNull(cancellation));
        expireIfNecessary();
        boolean cancelImmediately;
        synchronized (this) {
            cancelImmediately = cancellationReason != null;
            if (!closed && !cancelImmediately) {
                registrations.add(registration);
            }
        }
        if (cancelImmediately) {
            registration.cancel();
        }
        return registration;
    }

    public void cancel(CancellationReason reason) {
        Objects.requireNonNull(reason);
        List<Registration> pending;
        synchronized (this) {
            if (closed || cancellationReason != null) {
                return;
            }
            cancellationReason = reason;
            pending = List.copyOf(registrations);
            registrations.clear();
        }
        pending.forEach(Registration::cancel);
    }

    public void checkActive() {
        if (Thread.currentThread().isInterrupted()) {
            cancel(CancellationReason.CANCELLED);
        }
        expireIfNecessary();
        throwIfCancelled();
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("종료된 스토리보드 호출 컨텍스트는 재사용할 수 없습니다.");
            }
        }
    }

    /** Accept completion only before the deadline; late results and errors become TIMEOUT. */
    public void complete() {
        List<Registration> pending = List.of();
        synchronized (this) {
            if (!closed && cancellationReason == null && Thread.currentThread().isInterrupted()) {
                cancellationReason = CancellationReason.CANCELLED;
            } else if (!closed && cancellationReason == null && remainingTime().isZero()) {
                cancellationReason = CancellationReason.TIMEOUT;
            }
            if (!closed && cancellationReason != null) {
                pending = List.copyOf(registrations);
            }
            closed = true;
            registrations.clear();
        }
        pending.forEach(Registration::cancel);
        throwIfCancelled();
    }

    public synchronized AiGenerationException cancellationException(Throwable cause) {
        if (cancellationReason == CancellationReason.TIMEOUT) {
            return new AiGenerationException(AiGenerationErrorType.TIMEOUT,
                    "스토리보드 호출 제한 시간을 초과했습니다.", cause);
        }
        if (cancellationReason == CancellationReason.CANCELLED) {
            return new AiGenerationException(AiGenerationErrorType.PROVIDER_ERROR,
                    "스토리보드 호출이 취소되었습니다.", cause, AiProviderFailureType.CANCELLED);
        }
        throw new IllegalStateException("취소되지 않은 스토리보드 호출입니다.");
    }

    @Override
    public synchronized void close() {
        closed = true;
        registrations.clear();
    }

    private void expireIfNecessary() {
        if (remainingTime().isZero()) {
            cancel(CancellationReason.TIMEOUT);
        }
    }

    private synchronized void throwIfCancelled() {
        if (cancellationReason != null) {
            throw cancellationException(null);
        }
    }

    public enum CancellationReason {
        TIMEOUT,
        CANCELLED
    }

    public interface CancellationRegistration extends AutoCloseable {

        @Override
        void close();
    }

    private final class Registration implements CancellationRegistration {

        private final Runnable cancellation;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private Registration(Runnable cancellation) {
            this.cancellation = cancellation;
        }

        private void cancel() {
            if (!active.compareAndSet(true, false)) {
                return;
            }
            try {
                cancellation.run();
            } catch (RuntimeException exception) {
                // Transport exception messages may contain credentials or response content.
                log.warn("storyboard_attempt_cancellation_failed");
            }
        }

        @Override
        public void close() {
            active.set(false);
            synchronized (StoryboardAttemptContext.this) {
                registrations.remove(this);
            }
        }
    }
}
