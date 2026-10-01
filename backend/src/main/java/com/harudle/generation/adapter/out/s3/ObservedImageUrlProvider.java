package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Counts URL signing outcomes; it does not imply that the S3 object exists. */
public final class ObservedImageUrlProvider implements ImageUrlProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(ObservedImageUrlProvider.class);
    private final ImageUrlProvider delegate;
    private final MeterRegistry meterRegistry;

    public ObservedImageUrlProvider(ImageUrlProvider delegate, MeterRegistry meterRegistry) {
        this.delegate = Objects.requireNonNull(delegate);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    @Override
    public ImageAccessUrl createAccessUrl(String imageObjectKey) {
        long startedAt = System.nanoTime();
        String result = "failure";
        String failureType = "NONE";
        try {
            ImageAccessUrl url = delegate.createAccessUrl(imageObjectKey);
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
