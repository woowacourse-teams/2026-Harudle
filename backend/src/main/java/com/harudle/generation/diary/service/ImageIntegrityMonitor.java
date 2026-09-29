package com.harudle.generation.diary.service;

import com.harudle.generation.config.ImageIntegrityProperties;
import com.harudle.generation.diary.repository.ImageIntegrityCandidate;
import com.harudle.generation.diary.repository.ImageIntegrityCandidateRepository;
import com.harudle.generation.diary.service.port.ImageStorage;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;

/** Checks persisted user-visible image keys without listing or modifying S3 objects. */
public final class ImageIntegrityMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImageIntegrityMonitor.class);
    private static final String GENERATION_ID_MDC_KEY = "generationId";

    private final ImageIntegrityCandidateRepository candidates;
    private final ImageStorage imageStorage;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final ImageIntegrityProperties properties;
    private final AtomicLong lastCompletedSweepEpochSeconds = new AtomicLong();
    private final AtomicLong lastCompletedSweepSize = new AtomicLong();
    private final AtomicLong lastCompletedSweepMissing = new AtomicLong();
    private final AtomicLong lastCompletedSweepErrors = new AtomicLong();

    private UUID cursor;
    private long candidatesInSweep;
    private long missingInSweep;
    private long errorsInSweep;

    public ImageIntegrityMonitor(
            ImageIntegrityCandidateRepository candidates,
            ImageStorage imageStorage,
            MeterRegistry meterRegistry,
            Clock clock,
            ImageIntegrityProperties properties
    ) {
        this.candidates = Objects.requireNonNull(candidates);
        this.imageStorage = Objects.requireNonNull(imageStorage);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
        this.clock = Objects.requireNonNull(clock);
        this.properties = Objects.requireNonNull(properties);
        Gauge.builder("harudle.image.integrity.last.completed", lastCompletedSweepEpochSeconds, AtomicLong::get)
                .description("Unix time of the last complete traversal of eligible image records")
                .baseUnit("seconds")
                .register(meterRegistry);
        Gauge.builder("harudle.image.integrity.last.sweep.size", lastCompletedSweepSize, AtomicLong::get)
                .description("Number of eligible image records visited in the last complete traversal")
                .register(meterRegistry);
        Gauge.builder("harudle.image.integrity.last.sweep.missing", lastCompletedSweepMissing, AtomicLong::get)
                .description("Missing user-visible images found in the last complete traversal")
                .register(meterRegistry);
        Gauge.builder("harudle.image.integrity.last.sweep.errors", lastCompletedSweepErrors, AtomicLong::get)
                .description("Image checks that failed in the last complete traversal")
                .register(meterRegistry);
    }

    @Scheduled(
            fixedDelayString = "${harudle.image-integrity.interval:1m}",
            initialDelayString = "${harudle.image-integrity.initial-delay:1m}",
            scheduler = "imageIntegrityScheduler"
    )
    public synchronized void checkVisibleImages() {
        long startedAt = System.nanoTime();
        Instant completedBefore = clock.instant().minus(properties.minimumAge());
        int checkedThisRun = 0;
        String runResult = "partial";
        try {
            while (checkedThisRun < properties.maxChecksPerRun()
                    && System.nanoTime() - startedAt < properties.maxRunDuration().toNanos()) {
                int limit = Math.min(properties.pageSize(),
                        properties.maxChecksPerRun() - checkedThisRun);
                List<ImageIntegrityCandidate> page = candidates.findNext(cursor, completedBefore, limit);
                if (page.isEmpty()) {
                    lastCompletedSweepEpochSeconds.set(clock.instant().getEpochSecond());
                    lastCompletedSweepSize.set(candidatesInSweep);
                    lastCompletedSweepMissing.set(missingInSweep);
                    lastCompletedSweepErrors.set(errorsInSweep);
                    LOGGER.info("event=image_integrity_sweep_completed checked={} missing={} errors={}",
                            candidatesInSweep, missingInSweep, errorsInSweep);
                    cursor = null;
                    candidatesInSweep = 0;
                    missingInSweep = 0;
                    errorsInSweep = 0;
                    runResult = "complete";
                    break;
                }
                for (ImageIntegrityCandidate candidate : page) {
                    inspect(candidate, completedBefore);
                    cursor = candidate.generationId();
                    candidatesInSweep++;
                    checkedThisRun++;
                    if (checkedThisRun >= properties.maxChecksPerRun()
                            || System.nanoTime() - startedAt >= properties.maxRunDuration().toNanos()) {
                        break;
                    }
                }
            }
        } catch (RuntimeException exception) {
            runResult = "query_error";
            LOGGER.error("event=image_integrity_scan_failed exceptionType={}",
                    exception.getClass().getSimpleName());
        } finally {
            recordRun(runResult);
        }
    }

    private void inspect(ImageIntegrityCandidate candidate, Instant completedBefore) {
        String previousGenerationId = MDC.get(GENERATION_ID_MDC_KEY);
        MDC.put(GENERATION_ID_MDC_KEY, candidate.generationId().toString());
        try {
            if (imageStorage.exists(candidate.imageObjectKey())) {
                recordCheck("present");
            } else if (candidates.isStillVisible(candidate, completedBefore)) {
                missingInSweep++;
                recordCheck("missing");
                LOGGER.atError()
                        .addKeyValue("event", "image_missing")
                        .addKeyValue("generationId", candidate.generationId().toString())
                        .log("event=image_missing generationId={}", candidate.generationId());
            } else {
                recordCheck("ineligible");
            }
        } catch (RuntimeException exception) {
            errorsInSweep++;
            recordCheck("error");
            LOGGER.atWarn()
                    .addKeyValue("event", "image_integrity_check_failed")
                    .addKeyValue("generationId", candidate.generationId().toString())
                    .addKeyValue("exceptionType", exception.getClass().getSimpleName())
                    .log("event=image_integrity_check_failed generationId={} exceptionType={}",
                            candidate.generationId(), exception.getClass().getSimpleName());
        } finally {
            if (previousGenerationId == null) {
                MDC.remove(GENERATION_ID_MDC_KEY);
            } else {
                MDC.put(GENERATION_ID_MDC_KEY, previousGenerationId);
            }
        }
    }

    private void recordCheck(String result) {
        try {
            meterRegistry.counter("harudle.image.integrity.checks", "result", result).increment();
        } catch (RuntimeException exception) {
            LOGGER.warn("event=metrics_recording_failed component=image_integrity exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private void recordRun(String result) {
        try {
            meterRegistry.counter("harudle.image.integrity.runs", "result", result).increment();
        } catch (RuntimeException exception) {
            LOGGER.warn("event=metrics_recording_failed component=image_integrity exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
