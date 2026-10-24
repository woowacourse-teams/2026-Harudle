package com.harudle.generation.diary.service.storyboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.harudle.generation.diary.domain.StoryPanel;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.exception.AiProviderFailureType;
import com.harudle.generation.diary.service.port.StoryboardAttemptContext;
import com.harudle.generation.diary.service.port.StoryboardAttemptContext.CancellationReason;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import com.harudle.generation.diary.service.port.dto.GeneratedStoryboard;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

class StoryboardAttemptRunnerTest {

    private static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(300);

    private final StoryboardGenerationRequest request = new StoryboardGenerationRequest("일기", "프롬프트");
    private final GeneratedStoryboard result = new GeneratedStoryboard(storyboard(), null);
    private ExecutorService workers;
    private StoryboardAttemptRunner runner;
    private Map<String, String> previousMdc;

    @BeforeEach
    void setUp() {
        workers = Executors.newFixedThreadPool(2);
        runner = new StoryboardAttemptRunner(workers);
        previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        workers.shutdownNow();
        assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        MDC.setContextMap(previousMdc);
    }

    @Test
    @DisplayName("제한 시간 안에 생성하면 Provider를 한 번 호출하고 원래 요청은 변경하지 않는다")
    void generateOnceWithAttemptContext() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        AtomicReference<StoryboardAttemptContext> received = new AtomicReference<>();
        StoryboardGenerator generator = timedRequest -> {
            calls.incrementAndGet();
            assertThat(timedRequest.diaryText()).isEqualTo(request.diaryText());
            assertThat(timedRequest.storyboardPromptText()).isEqualTo(request.storyboardPromptText());
            var context = timedRequest.attemptContext().orElseThrow();
            received.set(context);
            context.onCancellation(cancellations::incrementAndGet);
            return result;
        };

        GeneratedStoryboard actual = run(generator);
        received.get().cancel(CancellationReason.TIMEOUT);

        assertThat(actual).isSameAs(result);
        assertThat(calls).hasValue(1);
        assertThat(cancellations).hasValue(0);
        assertThat(request.attemptContext()).isEmpty();
    }

    @Test
    @DisplayName("빠른 Provider 실패를 재시도 없이 그대로 반환하고 다음 호출은 별도 컨텍스트를 사용한다")
    void preserveEarlyErrorWithoutRetryOrBudgetReset() {
        AtomicLong clock = new AtomicLong();
        var budget = StoryboardGenerationBudget.start(TOTAL_TIMEOUT, clock::get);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<StoryboardAttemptContext> primaryContext = new AtomicReference<>();
        var error = new AiGenerationException(AiGenerationErrorType.RATE_LIMITED, "호출 한도 초과");
        StoryboardGenerator primary = timedRequest -> {
            calls.incrementAndGet();
            primaryContext.set(timedRequest.attemptContext().orElseThrow());
            clock.addAndGet(Duration.ofSeconds(5).toNanos());
            throw error;
        };

        assertThatThrownBy(() -> runner.run(primary, request, ATTEMPT_TIMEOUT, budget)).isSameAs(error);
        GeneratedStoryboard fallback = runner.run(timedRequest -> {
            var context = timedRequest.attemptContext().orElseThrow();
            assertThat(context).isNotSameAs(primaryContext.get());
            assertThat(context.remainingTime()).isEqualTo(ATTEMPT_TIMEOUT);
            return result;
        }, request, ATTEMPT_TIMEOUT, budget);

        assertThat(fallback).isSameAs(result);
        assertThat(calls).hasValue(1);
        assertThat(budget.remainingTime()).isEqualTo(Duration.ofSeconds(115));
    }

    @Test
    @DisplayName("제한 시간이 지나면 해당 요청의 콜백과 작업 인터럽트를 실행하고 TIMEOUT을 반환한다")
    void cancelTransportAndTaskOnTimeout() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        AtomicReference<StoryboardAttemptContext> received = new AtomicReference<>();
        CountDownLatch interrupted = new CountDownLatch(1);
        StoryboardGenerator generator = timedRequest -> {
            calls.incrementAndGet();
            var context = timedRequest.attemptContext().orElseThrow();
            received.set(context);
            context.onCancellation(cancellations::incrementAndGet);
            try {
                new CountDownLatch(1).await();
                return result;
            } catch (InterruptedException exception) {
                interrupted.countDown();
                throw new CancellationException();
            }
        };

        AiGenerationException failure = catchThrowableOfType(() -> runner.run(generator, request,
                SHORT_TIMEOUT, StoryboardGenerationBudget.start(TOTAL_TIMEOUT)), AiGenerationException.class);

        assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.TIMEOUT);
        assertThat(failure.providerFailureType()).isEqualTo(AiProviderFailureType.UNKNOWN);
        assertThat(interrupted.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get().cancellationReason()).contains(CancellationReason.TIMEOUT);
        assertThat(cancellations).hasValue(1);
        assertThat(calls).hasValue(1);
        assertThat(workers.isShutdown()).isFalse();
    }

    @Test
    @DisplayName("타임아웃 후 늦은 성공을 기다리지 않고 다음 호출을 수행하며 이전 결과를 버린다")
    void discardLateSuccessWithoutBlockingNextAttempt() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger lateCalls = new AtomicInteger();
        StoryboardGenerator slow = timedRequest -> {
            lateCalls.incrementAndGet();
            awaitIgnoringInterrupts(release);
            completed.countDown();
            return result;
        };
        var budget = StoryboardGenerationBudget.start(TOTAL_TIMEOUT);
        try {
            assertTimeout(() -> runner.run(slow, request, SHORT_TIMEOUT, budget));
            assertThat(completed.getCount()).isEqualTo(1);
            GeneratedStoryboard nextResult = new GeneratedStoryboard(storyboard(), null);

            assertThat(runner.run(timedRequest -> nextResult, request, ATTEMPT_TIMEOUT, budget))
                    .isSameAs(nextResult);
        } finally {
            release.countDown();
        }

        assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(lateCalls).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("제한 시간 이후 성공 또는 실패가 도착해도 TIMEOUT으로 종료한다")
    void rejectResultsCompletedAfterDeadline(boolean fail) {
        AtomicLong clock = new AtomicLong();
        var budget = StoryboardGenerationBudget.start(TOTAL_TIMEOUT, clock::get);
        AtomicInteger cancellations = new AtomicInteger();
        StoryboardGenerator generator = timedRequest -> {
            timedRequest.attemptContext().orElseThrow().onCancellation(cancellations::incrementAndGet);
            clock.addAndGet(Duration.ofSeconds(61).toNanos());
            if (fail) {
                throw new CancellationException();
            }
            return result;
        };

        assertTimeout(() -> runner.run(generator, request, ATTEMPT_TIMEOUT, budget));
        assertThat(cancellations).hasValue(1);
    }

    @Test
    @DisplayName("전체 예산이 소진되면 Provider를 호출하지 않는다")
    void skipProviderWhenTotalBudgetIsExhausted() {
        AtomicLong clock = new AtomicLong();
        var budget = StoryboardGenerationBudget.start(TOTAL_TIMEOUT, clock::get);
        clock.addAndGet(TOTAL_TIMEOUT.toNanos());
        AtomicInteger calls = new AtomicInteger();

        assertTimeout(() -> runner.run(timedRequest -> {
            calls.incrementAndGet();
            return result;
        }, request, ATTEMPT_TIMEOUT, budget));

        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("작업 대기 중에도 제한 시간이 적용되고 만료된 작업은 나중에 Provider를 호출하지 않는다")
    void includeQueueTimeInDeadline() throws Exception {
        workers.shutdownNow();
        workers = Executors.newSingleThreadExecutor();
        runner = new StoryboardAttemptRunner(workers);
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> blocker = workers.submit(() -> {
            occupied.countDown();
            awaitIgnoringInterrupts(release);
        });
        assertThat(occupied.await(3, TimeUnit.SECONDS)).isTrue();
        AtomicInteger calls = new AtomicInteger();
        try {
            assertTimeout(() -> runner.run(timedRequest -> {
                calls.incrementAndGet();
                return result;
            }, request, SHORT_TIMEOUT, StoryboardGenerationBudget.start(TOTAL_TIMEOUT)));
        } finally {
            release.countDown();
        }
        blocker.get(3, TimeUnit.SECONDS);
        workers.submit(() -> { }).get(3, TimeUnit.SECONDS);

        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("호출자 인터럽트는 HTTP와 작업을 취소하고 CANCELLED 및 인터럽트 상태를 유지한다")
    void cancelOnCallerInterruption() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch workerStopped = new CountDownLatch(1);
        AtomicInteger cancellations = new AtomicInteger();
        AtomicReference<AiGenerationException> failure = new AtomicReference<>();
        AtomicBoolean callerInterrupted = new AtomicBoolean();
        StoryboardGenerator generator = timedRequest -> {
            timedRequest.attemptContext().orElseThrow().onCancellation(cancellations::incrementAndGet);
            started.countDown();
            try {
                new CountDownLatch(1).await();
                return result;
            } catch (InterruptedException exception) {
                throw new CancellationException();
            } finally {
                workerStopped.countDown();
            }
        };
        Thread caller = new Thread(() -> {
            failure.set(catchThrowableOfType(() -> run(generator), AiGenerationException.class));
            callerInterrupted.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(3_000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(failure.get().errorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
        assertThat(failure.get().providerFailureType()).isEqualTo(AiProviderFailureType.CANCELLED);
        assertThat(callerInterrupted).isTrue();
        assertThat(cancellations).hasValue(1);
        assertThat(workerStopped.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("이미 인터럽트된 호출자는 Provider 작업을 시작하지 않는다")
    void skipProviderForAlreadyInterruptedCaller() {
        AtomicInteger calls = new AtomicInteger();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> run(timedRequest -> {
                calls.incrementAndGet();
                return result;
            })).isInstanceOfSatisfying(AiGenerationException.class, failure ->
                    assertThat(failure.providerFailureType()).isEqualTo(AiProviderFailureType.CANCELLED));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(calls).hasValue(0);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("Provider 자체의 명시적 취소도 TIMEOUT과 구분한다")
    void distinguishProviderCancellation() {
        assertThatThrownBy(() -> run(timedRequest -> {
            throw new CancellationException();
        })).isInstanceOfSatisfying(AiGenerationException.class, failure -> {
            assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
            assertThat(failure.providerFailureType()).isEqualTo(AiProviderFailureType.CANCELLED);
        });
    }

    @Test
    @DisplayName("한 요청이 타임아웃되어도 동시에 실행 중인 다른 요청의 콜백을 취소하지 않는다")
    void isolateConcurrentRequests() throws Exception {
        CountDownLatch healthyStarted = new CountDownLatch(1);
        CountDownLatch releaseHealthy = new CountDownLatch(1);
        AtomicInteger healthyCancellations = new AtomicInteger();
        try (ExecutorService callers = Executors.newSingleThreadExecutor()) {
            Future<GeneratedStoryboard> healthy = callers.submit(() -> run(timedRequest -> {
                timedRequest.attemptContext().orElseThrow().onCancellation(healthyCancellations::incrementAndGet);
                healthyStarted.countDown();
                awaitIgnoringInterrupts(releaseHealthy);
                return result;
            }));
            assertThat(healthyStarted.await(3, TimeUnit.SECONDS)).isTrue();
            try {
                assertTimeout(() -> runner.run(timedRequest -> {
                    awaitIgnoringInterrupts(releaseHealthy);
                    return result;
                }, request, SHORT_TIMEOUT, StoryboardGenerationBudget.start(TOTAL_TIMEOUT)));
            } finally {
                releaseHealthy.countDown();
            }
            assertThat(healthy.get(3, TimeUnit.SECONDS)).isSameAs(result);
        }

        assertThat(healthyCancellations).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("호출자의 generationId를 전달하고 완료 후 호출자와 작업 스레드 MDC를 복원한다")
    void propagateAndRestoreMdc(boolean callerHasContext) throws Exception {
        workers.shutdownNow();
        workers = Executors.newSingleThreadExecutor();
        runner = new StoryboardAttemptRunner(workers);
        workers.submit(() -> MDC.put("worker", "previous")).get(3, TimeUnit.SECONDS);
        Map<String, String> expectedCaller = callerHasContext
                ? Map.of("generationId", "generation-1", "traceId", "trace-1") : Map.of();
        MDC.setContextMap(expectedCaller);

        run(timedRequest -> {
            assertThat(MDC.getCopyOfContextMap()).isEqualTo(expectedCaller);
            MDC.put("provider", "temporary");
            return result;
        });

        assertThat(MDC.getCopyOfContextMap()).isEqualTo(expectedCaller);
        assertThat(workers.submit(MDC::getCopyOfContextMap).get(3, TimeUnit.SECONDS))
                .isEqualTo(Map.of("worker", "previous"));
    }

    @Test
    @DisplayName("실행기가 작업을 거절하면 Provider를 호출하지 않고 내부 오류로 전달한다")
    void reportTaskRejectionWithoutCallingProvider() {
        workers.shutdownNow();
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> run(timedRequest -> {
            calls.incrementAndGet();
            return result;
        })).isInstanceOfSatisfying(AiGenerationException.class, failure ->
                assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.INTERNAL_ERROR));

        assertThat(calls).hasValue(0);
    }

    private GeneratedStoryboard run(StoryboardGenerator generator) {
        return runner.run(generator, request, ATTEMPT_TIMEOUT, StoryboardGenerationBudget.start(TOTAL_TIMEOUT));
    }

    private static void assertTimeout(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AiGenerationException.class, failure ->
                assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.TIMEOUT));
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        boolean completed = false;
        while (!completed && System.nanoTime() < deadline) {
            try {
                completed = latch.await(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                // Simulate a transport that finishes late despite task interruption.
            }
        }
    }

    private static Storyboard storyboard() {
        List<StoryPanel> panels = IntStream.rangeClosed(1, 4)
                .mapToObj(number -> new StoryPanel(number, "캡션 " + number, "장면", "주인공", "놀람", List.of("소품")))
                .toList();
        return new Storyboard("제목", "같은 주인공이 등장한다.", panels);
    }
}
