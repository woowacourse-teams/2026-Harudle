package com.harudle.generation.adapter.out.s3;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class S3SigningMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(S3SigningMetrics.class);
    private final MeterRegistry meterRegistry;

    S3SigningMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    <T> T measure(Supplier<T> signing) {
        long startedAt = System.nanoTime();
        String result = "failure";
        String failureType = "NONE";
        try {
            T url = signing.get();
            result = "signed";
            return url;
        } catch (RuntimeException exception) {
            failureType = S3MetricFailureType.from(exception, true);
            throw exception;
        } finally {
            try {
                record(result, failureType, System.nanoTime() - startedAt);
            } catch (RuntimeException exception) {
                LOGGER.warn("event=metrics_recording_failed component=s3 operation=sign_url exceptionType={}",
                        exception.getClass().getSimpleName());
            }
        }
    }

    private void record(String result, String failureType, long durationNanos) {
        meterRegistry.counter("harudle.s3.url.signs", "result", result, "failureType", failureType).increment();
        Timer.builder("harudle.s3.url.sign.duration")
                .tags("result", result, "failureType", failureType)
                .register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }
}
