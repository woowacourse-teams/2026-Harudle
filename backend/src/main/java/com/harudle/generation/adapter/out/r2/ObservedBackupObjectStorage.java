package com.harudle.generation.adapter.out.r2;

import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.BackupStorageException;
import com.harudle.generation.diary.service.port.ImageLookupBudget;
import com.harudle.generation.diary.service.port.dto.BackupObjectMetadata;
import com.harudle.generation.diary.service.port.dto.BackupUploadResult;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import com.harudle.observability.RequestOperationTimings;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Observes logical R2 reads and signing, without object keys or request IDs as metric tags. */
public final class ObservedBackupObjectStorage implements BackupObjectStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(ObservedBackupObjectStorage.class);
    private final BackupObjectStorage delegate;
    private final MeterRegistry registry;

    public ObservedBackupObjectStorage(BackupObjectStorage delegate, MeterRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate);
        this.registry = Objects.requireNonNull(registry);
    }

    @Override
    public Optional<BackupObjectMetadata> findMetadata(String key) {
        return observe("head_object", RequestOperationTimings.Operation.R2_HEAD,
                () -> delegate.findMetadata(key), Optional::isEmpty);
    }

    @Override
    public Optional<BackupObjectMetadata> findMetadata(String key, ImageLookupBudget budget) {
        return observe("head_object", RequestOperationTimings.Operation.R2_HEAD,
                () -> delegate.findMetadata(key, budget), Optional::isEmpty);
    }

    @Override
    public ImageAccessUrl createAccessUrl(String key) {
        return observe("presign_get_object", RequestOperationTimings.Operation.R2_SIGN,
                () -> delegate.createAccessUrl(key), ignored -> false);
    }

    @Override
    public Optional<ReferenceImage> download(String key) {
        return delegate.download(key);
    }

    @Override
    public BackupUploadResult uploadIfAbsent(String key, GeneratedImage image) {
        return delegate.uploadIfAbsent(key, image);
    }

    private <T> T observe(String operation, RequestOperationTimings.Operation stage,
            Supplier<T> action, Predicate<T> isMissing) {
        long startedAt = System.nanoTime();
        String result = "failure";
        String failureType = "NONE";
        try {
            T value = action.get();
            result = isMissing.test(value) ? "missing" : "success";
            return value;
        } catch (RuntimeException exception) {
            failureType = exception instanceof BackupStorageException backupFailure
                    ? backupFailure.failureType().name() : "OTHER";
            throw exception;
        } finally {
            long elapsed = System.nanoTime() - startedAt;
            RequestOperationTimings.record(stage, elapsed, "failure".equals(result), "missing".equals(result));
            try {
                registry.counter("harudle.r2.operation.calls", "operation", operation,
                        "result", result, "failureType", failureType).increment();
                Timer.builder("harudle.r2.operation.duration")
                        .tags("operation", operation, "result", result, "failureType", failureType)
                        .register(registry).record(elapsed, TimeUnit.NANOSECONDS);
            } catch (RuntimeException exception) {
                LOGGER.warn("event=metrics_recording_failed component=r2 operation={} exceptionType={}",
                        operation, exception.getClass().getSimpleName());
            }
        }
    }
}
