package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** S3 operation metrics without object keys or request identifiers as tags. */
public final class ObservedImageStorage implements ImageStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(ObservedImageStorage.class);
    private final ImageStorage delegate;
    private final MeterRegistry meterRegistry;

    public ObservedImageStorage(ImageStorage delegate, MeterRegistry meterRegistry) {
        this.delegate = Objects.requireNonNull(delegate);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    @Override
    public ReferenceImage load(String imageObjectKey) {
        return observe("get_object", () -> delegate.load(imageObjectKey), ignored -> "success");
    }

    @Override
    public String store(UUID generationId, GeneratedImage generatedImage) {
        return observe("put_object", () -> delegate.store(generationId, generatedImage), ignored -> "success");
    }

    @Override
    public boolean exists(String imageObjectKey) {
        return observe("head_object", () -> delegate.exists(imageObjectKey),
                exists -> exists ? "present" : "missing");
    }

    @Override
    public boolean restoreIfMissing(String imageObjectKey, GeneratedImage generatedImage) {
        return observe("restore_object", () -> delegate.restoreIfMissing(imageObjectKey, generatedImage),
                restored -> restored ? "restored" : "already_present");
    }

    @Override
    public void delete(String imageObjectKey) {
        observe("delete_object", () -> {
            delegate.delete(imageObjectKey);
            return null;
        }, ignored -> "success");
    }

    private <T> T observe(String operation, Supplier<T> action, Function<T, String> resultForValue) {
        long startedAt = System.nanoTime();
        String result = "failure";
        try {
            T value = action.get();
            result = resultForValue.apply(value);
            return value;
        } finally {
            try {
                record(operation, result, System.nanoTime() - startedAt);
            } catch (RuntimeException exception) {
                LOGGER.warn("event=metrics_recording_failed component=s3 operation={} exceptionType={}",
                        operation, exception.getClass().getSimpleName());
            }
        }
    }

    private void record(String operation, String result, long durationNanos) {
        Counter.builder("harudle.s3.operation.calls")
                .description("Application-level S3 storage operations")
                .tags("operation", operation, "result", result)
                .register(meterRegistry)
                .increment();
        Timer.builder("harudle.s3.operation.duration")
                .description("S3 storage operation duration")
                .tags("operation", operation, "result", result)
                .register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }
}
