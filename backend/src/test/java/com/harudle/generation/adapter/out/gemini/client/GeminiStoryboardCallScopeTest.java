package com.harudle.generation.adapter.out.gemini.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.harudle.generation.diary.service.port.StoryboardAttemptContext;
import com.harudle.generation.diary.service.port.StoryboardAttemptContext.CancellationReason;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.EventListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GeminiStoryboardCallScopeTest {

    private final GeminiStoryboardCallScope scope = new GeminiStoryboardCallScope();

    @Test
    @DisplayName("HTTP Call 생성 중에는 변경하지 않고 시작 시 뒤늦은 취소를 연결한다")
    void registerOnlyAfterCallConstruction() {
        StoryboardAttemptContext context = context();
        Call call = mock(Call.class);

        scope.withContext(context, () -> {
            EventListener listener = scope.create(call);
            context.cancel(CancellationReason.TIMEOUT);
            verifyNoInteractions(call);

            listener.callStart(call);

            verify(call).cancel();
            return null;
        });
        assertThat(scope.create(mock(Call.class))).isSameAs(EventListener.NONE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("HTTP 완료와 실패에서 해당 Call의 취소 등록을 해제한다")
    void unregisterOnEndOrFailure(boolean failed) {
        StoryboardAttemptContext context = context();
        Call call = mock(Call.class);

        scope.withContext(context, () -> {
            EventListener listener = scope.create(call);
            listener.callStart(call);
            if (failed) {
                listener.callFailed(call, new IOException("fixture"));
            } else {
                listener.callEnd(call);
            }
            return null;
        });
        context.cancel(CancellationReason.TIMEOUT);

        verify(call, never()).cancel();
    }

    @Test
    @DisplayName("중첩 스코프를 벗어나면 이전 요청의 컨텍스트를 복원한다")
    void restorePreviousScope() {
        StoryboardAttemptContext outer = context();
        StoryboardAttemptContext inner = context();
        Call outerCall = mock(Call.class);
        Call innerCall = mock(Call.class);

        scope.withContext(outer, () -> {
            scope.withContext(inner, () -> {
                scope.create(innerCall).callStart(innerCall);
                return null;
            });
            scope.create(outerCall).callStart(outerCall);
            return null;
        });
        inner.cancel(CancellationReason.CANCELLED);
        verify(innerCall).cancel();
        verify(outerCall, never()).cancel();
        outer.cancel(CancellationReason.TIMEOUT);
        verify(outerCall).cancel();
    }

    @Test
    @DisplayName("SDK 호출이 실패해도 스레드의 요청 컨텍스트를 제거한다")
    void clearScopeAfterFailure() {
        AtomicReference<EventListener> captured = new AtomicReference<>();
        var failure = new IllegalStateException("fixture");

        assertThatThrownBy(() -> scope.withContext(context(), () -> {
            captured.set(scope.create(mock(Call.class)));
            throw failure;
        })).isSameAs(failure);

        assertThat(captured.get()).isNotSameAs(EventListener.NONE);
        assertThat(scope.create(mock(Call.class))).isSameAs(EventListener.NONE);
    }

    private static StoryboardAttemptContext context() {
        return new StoryboardAttemptContext(System.nanoTime() + Duration.ofSeconds(60).toNanos(), System::nanoTime);
    }
}
