package com.harudle.generation.diary.service.storyboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.harudle.generation.config.StoryboardProviderProperties;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
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
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class StoryboardGenerationServiceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(500);
    private final AtomicLong clock = new AtomicLong();
    private final StoryboardGenerationRequest request = new StoryboardGenerationRequest("일기 원문", "공통 프롬프트");
    private final GeneratedStoryboard primaryResult = result("메인 결과", new GenerationTokenUsage(100, 50, 20, 170));
    private final GeneratedStoryboard fallbackResult = result("보조 결과", new GenerationTokenUsage(200, 80, 40, 320));
    private ExecutorService workers;
    private StoryboardAttemptRunner runner;

    @BeforeEach
    void setUp() {
        workers = Executors.newFixedThreadPool(2);
        runner = new StoryboardAttemptRunner(workers);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        workers.shutdownNow();
        assertThat(workers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("메인이 성공하면 메인의 스토리보드와 토큰을 그대로 반환하고 보조는 호출하지 않는다")
    void returnPrimarySuccessWithoutCallingFallback() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        var service = service(input -> {
            primaryCalls.incrementAndGet();
            return primaryResult;
        }, input -> {
            fallbackCalls.incrementAndGet();
            return fallbackResult;
        });

        assertThat(service.generate(request)).isSameAs(primaryResult);
        assertThat(primaryCalls).hasValue(1);
        assertThat(fallbackCalls).hasValue(0);
    }

    @ParameterizedTest
    @CsvSource({"TIMEOUT,UNKNOWN", "RATE_LIMITED,UNKNOWN", "OUTPUT_TRUNCATED,UNKNOWN",
            "RESPONSE_PROCESSING_ERROR,UNKNOWN", "PROVIDER_ERROR,NETWORK_ERROR",
            "PROVIDER_ERROR,TRANSIENT_SERVER_ERROR"})
    @DisplayName("대체 가능한 메인 오류에만 보조를 한 번 호출하고 보조의 결과와 토큰을 반환한다")
    void fallBackOnceOnEligibleError(AiGenerationErrorType type, AiProviderFailureType detail) {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        var failure = failure(type, detail);
        var service = service(input -> {
            primaryCalls.incrementAndGet();
            throw failure;
        }, input -> {
            fallbackCalls.incrementAndGet();
            return fallbackResult;
        });

        assertThat(service.generate(request)).isSameAs(fallbackResult);
        assertThat(primaryCalls).hasValue(1);
        assertThat(fallbackCalls).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"PROVIDER_ERROR,UNKNOWN", "PROVIDER_ERROR,AUTH_CONFIGURATION_ERROR",
            "PROVIDER_ERROR,REQUEST_REJECTED", "PROVIDER_ERROR,CANCELLED", "TIMEOUT,CANCELLED",
            "RATE_LIMITED,AUTH_CONFIGURATION_ERROR", "RESPONSE_PROCESSING_ERROR,REQUEST_REJECTED",
            "INTERNAL_ERROR,UNKNOWN", "INTERNAL_ERROR,NETWORK_ERROR", "CONTENT_BLOCKED,UNKNOWN"})
    @DisplayName("인증 및 입력 오류와 취소 및 미분류 오류는 메인 오류를 유지하고 대체하지 않는다")
    void preserveIneligiblePrimaryFailure(AiGenerationErrorType type, AiProviderFailureType detail) {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        var failure = failure(type, detail);
        var service = service(input -> {
            primaryCalls.incrementAndGet();
            throw failure;
        }, input -> {
            fallbackCalls.incrementAndGet();
            return fallbackResult;
        });

        assertThatThrownBy(() -> service.generate(request)).isSameAs(failure);
        assertThat(primaryCalls).hasValue(1);
        assertThat(fallbackCalls).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(AiGenerationErrorType.class)
    @DisplayName("보조가 실패하면 그 오류로 종료하고 어느 Provider도 다시 호출하지 않는다")
    void preserveFinalFallbackFailureWithoutFurtherRetries(AiGenerationErrorType type) {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        var finalFailure = failure(type, AiProviderFailureType.UNKNOWN);
        var service = service(input -> {
            primaryCalls.incrementAndGet();
            throw failure(AiGenerationErrorType.RATE_LIMITED, AiProviderFailureType.UNKNOWN);
        }, input -> {
            fallbackCalls.incrementAndGet();
            throw finalFailure;
        });

        assertThatThrownBy(() -> service.generate(request)).isSameAs(finalFailure);
        assertThat(primaryCalls).hasValue(1);
        assertThat(fallbackCalls).hasValue(1);
        assertThat(finalFailure.getSuppressed()).isEmpty();
    }

    @Test
    @DisplayName("빠른 메인 실패 뒤 같은 입력과 새 취소 컨텍스트로 보조를 즉시 호출한다")
    void reuseInputWithFreshContextAfterEarlyFailure() {
        AtomicReference<StoryboardAttemptContext> primaryContext = new AtomicReference<>();
        AtomicInteger cancellations = new AtomicInteger();
        var service = service(input -> {
            primaryContext.set(input.attemptContext().orElseThrow());
            primaryContext.get().onCancellation(cancellations::incrementAndGet);
            clock.addAndGet(Duration.ofSeconds(5).toNanos());
            throw failure(AiGenerationErrorType.RATE_LIMITED, AiProviderFailureType.UNKNOWN);
        }, input -> {
            assertThat(input.diaryText()).isEqualTo(request.diaryText());
            assertThat(input.storyboardPromptText()).isEqualTo(request.storyboardPromptText());
            var context = input.attemptContext().orElseThrow();
            assertThat(context).isNotSameAs(primaryContext.get());
            assertThat(context.remainingTime()).isEqualTo(TIMEOUT);
            assertThat(context.cancellationReason()).isEmpty();
            return fallbackResult;
        });

        assertThat(service.generate(request)).isSameAs(fallbackResult);
        assertThat(clock).hasValue(Duration.ofSeconds(5).toNanos());
        assertThat(cancellations).hasValue(0);
        assertThat(request.attemptContext()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"gemini", "qwen"})
    @DisplayName("보조가 없는 단독 선택은 오류가 대체 가능해도 메인을 한 번만 호출한다")
    void preserveSingleProviderFailure(String provider) {
        AtomicInteger calls = new AtomicInteger();
        var failure = failure(AiGenerationErrorType.TIMEOUT, AiProviderFailureType.UNKNOWN);
        var properties = new StoryboardProviderProperties(provider, "none", TIMEOUT, TIMEOUT);
        var service = new StoryboardGenerationService(input -> {
            calls.incrementAndGet();
            throw failure;
        }, Optional.empty(), runner, properties, clock::get);

        assertThatThrownBy(() -> service.generate(request)).isSameAs(failure);
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"gemini,qwen", "qwen,gemini"})
    @DisplayName("Provider 이름과 관계없이 주입한 메인과 보조 포트를 순서대로 조합한다")
    void composeBothProviderOrders(String primary, String fallback) {
        var properties = new StoryboardProviderProperties(primary, fallback, TIMEOUT, TIMEOUT);
        var service = new StoryboardGenerationService(input -> {
            throw failure(AiGenerationErrorType.RATE_LIMITED, AiProviderFailureType.UNKNOWN);
        }, Optional.of(input -> fallbackResult), runner, properties, clock::get);

        assertThat(service.generate(request)).isSameAs(fallbackResult);
    }

    @Test
    @DisplayName("메인 제한 시간 초과 후 취소가 실행된 다음 보조를 한 번 호출한다")
    void startFallbackAfterPrimaryTimeoutAndCancellation() throws InterruptedException {
        AtomicInteger cancellations = new AtomicInteger();
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        CountDownLatch stopped = new CountDownLatch(1);
        StoryboardGenerator primary = input -> {
            primaryCalls.incrementAndGet();
            input.attemptContext().orElseThrow().onCancellation(cancellations::incrementAndGet);
            return blockUntilInterrupted(stopped);
        };
        var service = realService(primary, input -> {
            fallbackCalls.incrementAndGet();
            assertThat(cancellations).hasValue(1);
            assertThat(input.attemptContext().orElseThrow().cancellationReason()).isEmpty();
            return fallbackResult;
        });

        assertThat(service.generate(request)).isSameAs(fallbackResult);
        assertThat(stopped.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(primaryCalls).hasValue(1);
        assertThat(fallbackCalls).hasValue(1);
    }

    @Test
    @DisplayName("보조 제한 시간 초과는 최종 TIMEOUT으로 종료하고 메인으로 돌아가지 않는다")
    void stopAfterFallbackTimeout() throws InterruptedException {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        CountDownLatch stopped = new CountDownLatch(1);
        var service = realService(input -> {
            primaryCalls.incrementAndGet();
            throw failure(AiGenerationErrorType.RATE_LIMITED, AiProviderFailureType.UNKNOWN);
        }, input -> {
            fallbackCalls.incrementAndGet();
            input.attemptContext().orElseThrow().onCancellation(cancellations::incrementAndGet);
            return blockUntilInterrupted(stopped);
        });

        assertTimeout(() -> service.generate(request));
        assertThat(stopped.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(primaryCalls).hasValue(1);
        assertThat(fallbackCalls).hasValue(1);
        assertThat(cancellations).hasValue(1);
    }

    @Test
    @DisplayName("보조의 제한 시간은 메인과 공유하는 전체 예산의 남은 시간으로 줄인다")
    void shareTotalBudgetWithoutGivingFallbackFreshTotalTimeout() {
        var service = service(input -> {
            clock.addAndGet(Duration.ofSeconds(61).toNanos());
            return primaryResult;
        }, input -> {
            assertThat(input.attemptContext().orElseThrow().remainingTime()).isEqualTo(Duration.ofSeconds(59));
            clock.addAndGet(Duration.ofSeconds(58).toNanos());
            return fallbackResult;
        });

        assertThat(service.generate(request)).isSameAs(fallbackResult);
        assertThat(clock).hasValue(Duration.ofSeconds(119).toNanos());
    }

    @Test
    @DisplayName("전체 120초 경계에 도착한 보조 결과는 최종 TIMEOUT으로 거절한다")
    void rejectFallbackResultAtTotalDeadline() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        var service = service(input -> {
            clock.addAndGet(Duration.ofSeconds(61).toNanos());
            return primaryResult;
        }, input -> {
            fallbackCalls.incrementAndGet();
            clock.addAndGet(Duration.ofSeconds(59).toNanos());
            return fallbackResult;
        });

        assertTimeout(() -> service.generate(request));
        assertThat(fallbackCalls).hasValue(1);
        assertThat(clock).hasValue(Duration.ofSeconds(120).toNanos());
    }

    @Test
    @DisplayName("전체 시간 예산이 이미 소진됐으면 보조 Provider를 호출하지 않는다")
    void skipFallbackProviderWhenBudgetIsExhausted() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        var service = service(input -> {
            clock.addAndGet(Duration.ofSeconds(120).toNanos());
            throw failure(AiGenerationErrorType.RATE_LIMITED, AiProviderFailureType.UNKNOWN);
        }, input -> {
            fallbackCalls.incrementAndGet();
            return fallbackResult;
        });

        assertTimeout(() -> service.generate(request));
        assertThat(fallbackCalls).hasValue(0);
    }

    @Test
    @DisplayName("싱글턴 서비스라도 전체 예산은 각 생성 요청에서 새로 시작한다")
    void createBudgetPerGenerationRequest() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        var service = service(input -> {
            int call = primaryCalls.incrementAndGet();
            clock.addAndGet(Duration.ofSeconds(call == 1 ? 61 : 10).toNanos());
            throw failure(AiGenerationErrorType.RATE_LIMITED, AiProviderFailureType.UNKNOWN);
        }, input -> {
            int call = fallbackCalls.incrementAndGet();
            clock.addAndGet(Duration.ofSeconds(call == 1 ? 58 : 1).toNanos());
            return fallbackResult;
        });

        assertThat(service.generate(request)).isSameAs(fallbackResult);
        assertThat(service.generate(request)).isSameAs(fallbackResult);
        assertThat(primaryCalls).hasValue(2);
        assertThat(fallbackCalls).hasValue(2);
        assertThat(clock).hasValue(Duration.ofSeconds(130).toNanos());
    }

    @Test
    @DisplayName("알 수 없는 런타임 오류는 재시도 가능한 오류로 추측하지 않는다")
    void preserveUnexpectedPrimaryFailure() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        var error = new IllegalStateException("fixture");
        var service = service(input -> {
            throw error;
        }, input -> {
            fallbackCalls.incrementAndGet();
            return fallbackResult;
        });

        assertThatThrownBy(() -> service.generate(request)).isSameAs(error);
        assertThat(fallbackCalls).hasValue(0);
    }

    @Test
    @DisplayName("보조 호출 자체가 취소되면 추가 대체 없이 CANCELLED로 종료한다")
    void stopAfterExplicitFallbackCancellation() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        var service = service(input -> {
            primaryCalls.incrementAndGet();
            throw failure(AiGenerationErrorType.RATE_LIMITED, AiProviderFailureType.UNKNOWN);
        }, input -> {
            fallbackCalls.incrementAndGet();
            input.attemptContext().orElseThrow().cancel(CancellationReason.CANCELLED);
            return fallbackResult;
        });

        assertThatThrownBy(() -> service.generate(request)).isInstanceOfSatisfying(AiGenerationException.class,
                error -> assertThat(error.providerFailureType()).isEqualTo(AiProviderFailureType.CANCELLED));
        assertThat(primaryCalls).hasValue(1);
        assertThat(fallbackCalls).hasValue(1);
    }

    @Test
    @DisplayName("이미 인터럽트된 호출자는 메인과 보조 모두 호출하지 않는다")
    void skipProvidersForAlreadyInterruptedCaller() {
        AtomicInteger calls = new AtomicInteger();
        StoryboardGenerator provider = input -> {
            calls.incrementAndGet();
            return primaryResult;
        };
        var service = service(provider, input -> {
            calls.incrementAndGet();
            return fallbackResult;
        });
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> service.generate(request)).isInstanceOfSatisfying(AiGenerationException.class,
                    error -> assertThat(error.providerFailureType()).isEqualTo(AiProviderFailureType.CANCELLED));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(calls).hasValue(0);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("메인 실행 중 호출자가 인터럽트되면 취소 상태를 유지하고 보조를 호출하지 않는다")
    void stopWithoutFallbackOnCallerInterruption() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicInteger fallbackCalls = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        AtomicReference<AiGenerationException> failure = new AtomicReference<>();
        AtomicBoolean callerInterrupted = new AtomicBoolean();
        var service = service(input -> {
            input.attemptContext().orElseThrow().onCancellation(cancellations::incrementAndGet);
            started.countDown();
            return blockUntilInterrupted(stopped);
        }, input -> {
            fallbackCalls.incrementAndGet();
            return fallbackResult;
        });
        Thread caller = new Thread(() -> {
            failure.set(catchThrowableOfType(() -> service.generate(request), AiGenerationException.class));
            callerInterrupted.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        try {
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(3_000);

            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get().providerFailureType()).isEqualTo(AiProviderFailureType.CANCELLED);
            assertThat(callerInterrupted).isTrue();
            assertThat(cancellations).hasValue(1);
            assertThat(fallbackCalls).hasValue(0);
            assertThat(stopped.await(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            caller.interrupt();
            caller.join(3_000);
        }
    }

    @Test
    @DisplayName("보조 선택 설정과 실제 구현체 유무가 다르면 조합을 거절한다")
    void rejectInconsistentFallbackConfiguration() {
        StoryboardGenerator primary = input -> primaryResult;
        StoryboardGenerator fallback = input -> fallbackResult;
        var enabled = new StoryboardProviderProperties("gemini", "qwen", TIMEOUT, TIMEOUT);
        var disabled = new StoryboardProviderProperties("gemini", "none", TIMEOUT, TIMEOUT);

        assertThatThrownBy(() -> new StoryboardGenerationService(primary, Optional.empty(), runner, enabled))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoryboardGenerationService(primary, Optional.of(fallback), runner, disabled))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoryboardGenerationService(primary, Optional.of(primary), runner, enabled))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private StoryboardGenerationService service(StoryboardGenerator primary, StoryboardGenerator fallback) {
        var properties = new StoryboardProviderProperties("gemini", "qwen", TIMEOUT, TIMEOUT);
        return new StoryboardGenerationService(primary, Optional.of(fallback), runner, properties, clock::get);
    }

    private StoryboardGenerationService realService(StoryboardGenerator primary, StoryboardGenerator fallback) {
        var properties = new StoryboardProviderProperties("gemini", "qwen", SHORT_TIMEOUT, SHORT_TIMEOUT);
        return new StoryboardGenerationService(primary, Optional.of(fallback), runner, properties);
    }

    private static GeneratedStoryboard blockUntilInterrupted(CountDownLatch stopped) {
        try {
            new CountDownLatch(1).await();
            throw new IllegalStateException("대기 중인 Provider가 취소 없이 종료되었습니다.");
        } catch (InterruptedException exception) {
            throw new CancellationException();
        } finally {
            stopped.countDown();
        }
    }

    private static AiGenerationException failure(AiGenerationErrorType type, AiProviderFailureType detail) {
        return new AiGenerationException(type, "fixture", null, detail);
    }

    private static void assertTimeout(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AiGenerationException.class,
                failure -> assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.TIMEOUT));
    }

    private static GeneratedStoryboard result(String title, GenerationTokenUsage usage) {
        List<StoryPanel> panels = IntStream.rangeClosed(1, 4)
                .mapToObj(number -> new StoryPanel(number, "캡션 " + number, "장면", "주인공", "놀람", List.of("소품")))
                .toList();
        return new GeneratedStoryboard(new Storyboard(title, "같은 주인공이 등장한다.", panels), usage);
    }
}
