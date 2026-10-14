package com.harudle.generation.diary.service.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.exception.AiProviderFailureType;
import com.harudle.generation.diary.service.port.StoryboardAttemptContext.CancellationReason;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StoryboardAttemptContextTest {

    private final AtomicLong clock = new AtomicLong();
    private final StoryboardAttemptContext context = new StoryboardAttemptContext(
            Duration.ofSeconds(60).toNanos(), clock::get);

    @Test
    @DisplayName("제한 시간 초과 후 다른 취소가 발생해도 TIMEOUT 사유와 콜백 1회를 유지한다")
    void retainDeadlineReasonAndCancelOnce() {
        AtomicInteger cancellations = new AtomicInteger();
        context.onCancellation(cancellations::incrementAndGet);
        clock.set(Duration.ofSeconds(60).toNanos());

        assertThatThrownBy(context::checkActive).isInstanceOfSatisfying(AiGenerationException.class, failure -> {
            assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.TIMEOUT);
            assertThat(failure.providerFailureType()).isEqualTo(AiProviderFailureType.UNKNOWN);
        });
        context.cancel(CancellationReason.CANCELLED);
        context.cancel(CancellationReason.TIMEOUT);

        assertThat(context.cancellationReason()).contains(CancellationReason.TIMEOUT);
        assertThat(cancellations).hasValue(1);
    }

    @Test
    @DisplayName("명시적 취소는 제한 시간이 지나도 CANCELLED로 구분한다")
    void retainExplicitCancellationReason() {
        context.cancel(CancellationReason.CANCELLED);
        clock.set(Duration.ofSeconds(61).toNanos());

        assertThatThrownBy(context::checkActive).isInstanceOfSatisfying(AiGenerationException.class, failure -> {
            assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
            assertThat(failure.providerFailureType()).isEqualTo(AiProviderFailureType.CANCELLED);
        });
        assertThat(context.cancellationReason()).contains(CancellationReason.CANCELLED);
    }

    @Test
    @DisplayName("이미 취소된 호출에 뒤늦게 등록한 HTTP 취소 콜백도 즉시 실행한다")
    void cancelLateRegisteredTransport() {
        AtomicInteger cancellations = new AtomicInteger();
        context.cancel(CancellationReason.TIMEOUT);
        context.close();

        context.onCancellation(cancellations::incrementAndGet);

        assertThat(cancellations).hasValue(1);
    }

    @Test
    @DisplayName("아직 취소를 통보받지 않아도 만료된 컨텍스트의 콜백은 즉시 실행한다")
    void cancelTransportRegisteredAfterDeadline() {
        AtomicInteger cancellations = new AtomicInteger();
        clock.set(Duration.ofSeconds(61).toNanos());

        context.onCancellation(cancellations::incrementAndGet);

        assertThat(cancellations).hasValue(1);
        assertThat(context.cancellationReason()).contains(CancellationReason.TIMEOUT);
    }

    @Test
    @DisplayName("제한 시간 안에 완료된 요청의 콜백을 정리하고 이후 취소하지 않는다")
    void releaseHooksAfterSuccessfulCompletion() {
        AtomicInteger cancellations = new AtomicInteger();
        context.onCancellation(cancellations::incrementAndGet);

        context.complete();
        clock.set(Duration.ofSeconds(61).toNanos());
        context.cancel(CancellationReason.TIMEOUT);
        context.onCancellation(cancellations::incrementAndGet);

        assertThat(cancellations).hasValue(0);
        assertThat(context.cancellationReason()).isEmpty();
    }

    @Test
    @DisplayName("HTTP가 끝나 콜백 등록을 해제하면 해당 콜백은 취소 때 호출되지 않는다")
    void removeTransportHook() {
        AtomicInteger removed = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        var registration = context.onCancellation(removed::incrementAndGet);
        context.onCancellation(active::incrementAndGet);

        registration.close();
        context.cancel(CancellationReason.TIMEOUT);

        assertThat(removed).hasValue(0);
        assertThat(active).hasValue(1);
    }

    @Test
    @DisplayName("한 HTTP 취소 콜백이 실패해도 다른 콜백과 TIMEOUT 분류를 유지한다")
    void continueCancellationAfterHookFailure() {
        AtomicInteger cancellations = new AtomicInteger();
        context.onCancellation(() -> {
            throw new IllegalStateException("취소 실패");
        });
        context.onCancellation(cancellations::incrementAndGet);

        context.cancel(CancellationReason.TIMEOUT);

        assertThat(cancellations).hasValue(1);
        assertThat(context.cancellationException(null).errorType()).isEqualTo(AiGenerationErrorType.TIMEOUT);
    }

    @Test
    @DisplayName("제한 시간을 지난 완료 결과를 거절하고 해당 요청을 취소한다")
    void rejectCompletionAtDeadline() {
        AtomicInteger cancellations = new AtomicInteger();
        context.onCancellation(cancellations::incrementAndGet);
        clock.set(Duration.ofSeconds(60).toNanos());

        assertThatThrownBy(context::complete).isInstanceOfSatisfying(AiGenerationException.class, failure ->
                assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.TIMEOUT));
        assertThat(cancellations).hasValue(1);
    }

    @Test
    @DisplayName("완료 직전 호출자가 인터럽트되면 취소하고 인터럽트 상태를 유지한다")
    void cancelInterruptedCompletion() {
        AtomicInteger cancellations = new AtomicInteger();
        context.onCancellation(cancellations::incrementAndGet);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(context::complete).isInstanceOfSatisfying(AiGenerationException.class, failure ->
                    assertThat(failure.providerFailureType()).isEqualTo(AiProviderFailureType.CANCELLED));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(cancellations).hasValue(1);
        } finally {
            Thread.interrupted();
        }
    }
}
