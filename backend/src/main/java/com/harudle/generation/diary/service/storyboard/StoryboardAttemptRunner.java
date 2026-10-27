package com.harudle.generation.diary.service.storyboard;

import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.port.StoryboardAttemptContext;
import com.harudle.generation.diary.service.port.StoryboardAttemptContext.CancellationReason;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import com.harudle.generation.diary.service.port.dto.GeneratedStoryboard;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.MDC;

/** Runs a provider exactly once. Provider selection and fallback policy belong to the service. */
public final class StoryboardAttemptRunner {

    private final ExecutorService executor;

    /** The caller owns the executor lifecycle; cancellation never shuts down the shared executor. */
    public StoryboardAttemptRunner(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor);
    }

    public GeneratedStoryboard run(
            StoryboardGenerator generator,
            StoryboardGenerationRequest request,
            Duration timeout,
            StoryboardGenerationBudget budget
    ) {
        Objects.requireNonNull(generator);
        Objects.requireNonNull(request);
        StoryboardAttemptContext context = Objects.requireNonNull(budget).newAttempt(timeout);
        Future<GeneratedStoryboard> task = null;
        try {
            context.checkActive();
            Map<String, String> callerMdc = MDC.getCopyOfContextMap();
            task = submit(generator, request, context, callerMdc);
            return await(task, context);
        } catch (TimeoutException exception) {
            context.cancel(CancellationReason.TIMEOUT);
            throw context.cancellationException(exception);
        } catch (InterruptedException exception) {
            context.cancel(CancellationReason.CANCELLED);
            Thread.currentThread().interrupt();
            throw context.cancellationException(exception);
        } catch (CancellationException exception) {
            context.cancel(CancellationReason.CANCELLED);
            throw context.cancellationException(exception);
        } finally {
            if (task != null && context.cancellationReason().isPresent()) {
                task.cancel(true);
            }
            context.close();
        }
    }

    private Future<GeneratedStoryboard> submit(
            StoryboardGenerator generator,
            StoryboardGenerationRequest request,
            StoryboardAttemptContext context,
            Map<String, String> callerMdc
    ) {
        try {
            return executor.submit(() -> generate(generator, request, context, callerMdc));
        } catch (RejectedExecutionException exception) {
            throw new AiGenerationException(AiGenerationErrorType.INTERNAL_ERROR,
                    "스토리보드 호출 작업을 시작할 수 없습니다.", exception);
        }
    }

    private GeneratedStoryboard await(Future<GeneratedStoryboard> task, StoryboardAttemptContext context)
            throws InterruptedException, TimeoutException {
        try {
            GeneratedStoryboard result = task.get(context.remainingTime().toNanos(), TimeUnit.NANOSECONDS);
            context.complete();
            return result;
        } catch (ExecutionException exception) {
            context.checkActive();
            if (exception.getCause() instanceof CancellationException
                    || exception.getCause() instanceof InterruptedException) {
                context.cancel(CancellationReason.CANCELLED);
            }
            context.complete();
            throw propagate(exception.getCause());
        }
    }

    private GeneratedStoryboard generate(
            StoryboardGenerator generator,
            StoryboardGenerationRequest request,
            StoryboardAttemptContext context,
            Map<String, String> callerMdc
    ) {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        replaceMdc(callerMdc);
        try {
            context.checkActive();
            return generator.generate(request.withAttemptContext(context));
        } finally {
            replaceMdc(previousMdc);
        }
    }

    private static RuntimeException propagate(Throwable cause) {
        if (cause instanceof RuntimeException exception) {
            return exception;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new AiGenerationException(AiGenerationErrorType.INTERNAL_ERROR,
                "스토리보드 호출 작업이 실패했습니다.", cause);
    }

    private static void replaceMdc(Map<String, String> values) {
        if (values == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(values);
        }
    }
}
