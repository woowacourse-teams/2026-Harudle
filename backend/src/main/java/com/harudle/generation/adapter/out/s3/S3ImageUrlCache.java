package com.harudle.generation.adapter.out.s3;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.harudle.generation.config.S3UrlCacheProperties;
import com.harudle.generation.diary.service.port.ImageLookupBudget;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class S3ImageUrlCache implements AutoCloseable {

    private static final Duration FAILURE_RETRY_DELAY = Duration.ofSeconds(5);
    private static final Logger LOGGER = LoggerFactory.getLogger(S3ImageUrlCache.class);

    private final Cache<String, SignedS3ImageUrl> entries;
    private final Map<String, CompletableFuture<SignedS3ImageUrl>> refreshes = new HashMap<>();
    private final Map<String, Instant> retryAfter = new HashMap<>();
    private final ThreadPoolExecutor executor;
    private final Semaphore waiters;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final Duration waitTimeout;
    private final int maximumRetryEntries;
    private final MeterRegistry meterRegistry;
    private final Timer waitDuration;
    private boolean closed;

    public S3ImageUrlCache(S3UrlCacheProperties properties, Clock clock, MeterRegistry meterRegistry) {
        this(properties, clock, meterRegistry, System::nanoTime);
    }

    S3ImageUrlCache(S3UrlCacheProperties properties, Clock clock, MeterRegistry meterRegistry, LongSupplier nanoTime) {
        this.clock = clock;
        this.nanoTime = nanoTime;
        this.waitTimeout = properties.waitTimeout();
        this.maximumRetryEntries = properties.maximumSize();
        this.meterRegistry = meterRegistry;
        this.waitDuration = Timer.builder("harudle.s3.url.cache.wait.duration")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(1))
                .register(meterRegistry);
        this.waiters = new Semaphore(properties.maximumWaiters());
        this.entries = Caffeine.newBuilder().maximumSize(properties.maximumSize())
                .evictionListener((key, value, cause) -> recordEviction(cause.name()))
                .build();
        this.executor = new ThreadPoolExecutor(
                properties.refreshConcurrency(), properties.refreshConcurrency(), 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.maximumWaiters()),
                Thread.ofPlatform().daemon().name("s3-url-refresh-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
        registerMetrics(properties.maximumWaiters());
    }

    public static boolean supportsTtl(Duration ttl) {
        return SignedS3ImageUrl.supportsCache(ttl);
    }

    ImageLookupBudget newLookupBudget() {
        return new ImageLookupBudget(waitTimeout, nanoTime);
    }

    ImageAccessUrl accessUrlFor(String imageObjectKey, Supplier<SignedS3ImageUrl> signer, ImageLookupBudget budget) {
        Optional<SignedS3ImageUrl> previous = entryFor(imageObjectKey);
        Optional<SignedS3ImageUrl> reusable = previous.filter(url -> url.canReuseAt(clock.instant()));
        if (reusable.isPresent()) {
            record("hit");
            return reusable.orElseThrow().accessUrl();
        }
        record("miss");
        if (!waiters.tryAcquire()) {
            record("wait_rejected");
            return existingUrlOrThrow(previous, unavailable());
        }
        try {
            return await(refresh(imageObjectKey, signer, budget), budget);
        } catch (ImageStorageException exception) {
            return existingUrlOrThrow(entryFor(imageObjectKey).or(() -> previous), exception);
        } finally {
            waiters.release();
        }
    }

    private synchronized CompletableFuture<SignedS3ImageUrl> refresh(
            String imageObjectKey,
            Supplier<SignedS3ImageUrl> signer,
            ImageLookupBudget budget
    ) {
        if (closed) {
            throw unavailable();
        }
        Instant now = clock.instant();
        Optional<SignedS3ImageUrl> reusable = entryFor(imageObjectKey).filter(url -> url.canReuseAt(now));
        if (reusable.isPresent()) {
            return CompletableFuture.completedFuture(reusable.orElseThrow());
        }
        CompletableFuture<SignedS3ImageUrl> current = refreshes.get(imageObjectKey);
        if (current != null) {
            record("coalesced");
            return current;
        }
        if (budget.remainingNanos() == 0) {
            record("wait_timeout");
            throw unavailable();
        }
        requireRefreshAvailable(imageObjectKey, now);
        CompletableFuture<SignedS3ImageUrl> started = new CompletableFuture<>();
        ImageLookupBudget queueBudget = newLookupBudget();
        refreshes.put(imageObjectKey, started);
        try {
            executor.execute(() -> sign(imageObjectKey, signer, started, queueBudget));
        } catch (RejectedExecutionException exception) {
            refreshes.remove(imageObjectKey, started);
            record("refresh_rejected");
            started.completeExceptionally(unavailable());
        }
        return started;
    }

    private void requireRefreshAvailable(String imageObjectKey, Instant now) {
        Instant retryAt = retryAfter.get(imageObjectKey);
        if (retryAt != null && retryAt.isAfter(now)) {
            record("cooldown");
            throw unavailable();
        }
        retryAfter.remove(imageObjectKey);
        if (retryAfter.size() + refreshes.size() < maximumRetryEntries) {
            return;
        }
        // Reserve space for a possible failure before starting work, even if URL entries are evicted.
        retryAfter.values().removeIf(expiration -> !expiration.isAfter(now));
        if (retryAfter.size() + refreshes.size() >= maximumRetryEntries) {
            record("retry_capacity_rejected");
            throw unavailable();
        }
    }

    private void sign(String imageObjectKey, Supplier<SignedS3ImageUrl> signer,
            CompletableFuture<SignedS3ImageUrl> result, ImageLookupBudget queueBudget) {
        try {
            if (queueBudget.remainingNanos() == 0) {
                record("refresh_queue_expired");
                return;
            }
            SignedS3ImageUrl signedUrl = signer.get();
            requireUsableUrl(signedUrl);
            publish(imageObjectKey, signedUrl, result);
        } catch (RuntimeException exception) {
            fail(imageObjectKey, result, exception);
        } finally {
            finish(imageObjectKey, result);
        }
    }

    private void requireUsableUrl(SignedS3ImageUrl signedUrl) {
        if (!signedUrl.canServeAt(clock.instant())) {
            record("invalid_expiration");
            throw new ImageStorageException("새 S3 이미지 주소의 잔여 유효 시간이 부족합니다.");
        }
    }

    private synchronized void publish(String imageObjectKey, SignedS3ImageUrl signedUrl,
            CompletableFuture<SignedS3ImageUrl> result) {
        if (closed) {
            result.completeExceptionally(unavailable());
            return;
        }
        if (signedUrl.credentialExpirationKnown()) {
            entries.put(imageObjectKey, signedUrl);
            record("refresh_success");
        } else {
            record("bypass_unknown_expiration");
        }
        refreshes.remove(imageObjectKey, result);
        result.complete(signedUrl);
    }

    private synchronized void fail(String imageObjectKey,
            CompletableFuture<SignedS3ImageUrl> result, RuntimeException exception) {
        if (!closed) {
            retryAfter.put(imageObjectKey, clock.instant().plus(FAILURE_RETRY_DELAY));
            record("refresh_failure");
        }
        refreshes.remove(imageObjectKey, result);
        result.completeExceptionally(exception);
    }

    private synchronized void finish(String imageObjectKey, CompletableFuture<SignedS3ImageUrl> result) {
        if (refreshes.remove(imageObjectKey, result)) {
            result.completeExceptionally(unavailable());
        }
    }

    private ImageAccessUrl await(CompletableFuture<SignedS3ImageUrl> result, ImageLookupBudget budget) {
        long startedAt = System.nanoTime();
        try {
            SignedS3ImageUrl signedUrl = result.get(budget.remainingNanos(), TimeUnit.NANOSECONDS);
            requireUsableUrl(signedUrl);
            return signedUrl.accessUrl();
        } catch (TimeoutException exception) {
            record("wait_timeout");
            throw unavailable();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            record("wait_interrupted");
            throw unavailable();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof ImageStorageException failure) {
                throw failure;
            }
            throw unavailable();
        } finally {
            recordWaitDuration(System.nanoTime() - startedAt);
        }
    }

    private ImageAccessUrl existingUrlOrThrow(Optional<SignedS3ImageUrl> previous, ImageStorageException failure) {
        Optional<SignedS3ImageUrl> valid = previous.filter(url -> url.isValidAt(clock.instant()));
        if (valid.isPresent()) {
            record("stale");
            return valid.orElseThrow().accessUrl();
        }
        throw failure;
    }

    private Optional<SignedS3ImageUrl> entryFor(String imageObjectKey) {
        return Optional.ofNullable(entries.getIfPresent(imageObjectKey));
    }

    private void registerMetrics(int maximumWaiters) {
        meterRegistry.gauge("harudle.s3.url.cache.size", entries, Cache::estimatedSize);
        meterRegistry.gauge("harudle.s3.url.cache.refresh.active", executor, ThreadPoolExecutor::getActiveCount);
        meterRegistry.gauge("harudle.s3.url.cache.refresh.queued", executor, pool -> pool.getQueue().size());
        meterRegistry.gauge("harudle.s3.url.cache.waiters", waiters,
                permits -> maximumWaiters - permits.availablePermits());
        List.of("hit", "miss", "coalesced", "cooldown", "stale", "refresh_success", "refresh_failure",
                        "bypass_unknown_expiration", "invalid_expiration", "wait_timeout", "wait_interrupted",
                        "wait_rejected", "refresh_rejected", "refresh_queue_expired", "retry_capacity_rejected")
                .forEach(result -> meterRegistry.counter("harudle.s3.url.cache.events", "result", result));
    }

    private void record(String result) {
        safelyRecord(() -> meterRegistry.counter("harudle.s3.url.cache.events", "result", result).increment());
    }

    private void recordEviction(String cause) {
        safelyRecord(() -> meterRegistry.counter("harudle.s3.url.cache.evictions", "cause", cause).increment());
    }

    private void recordWaitDuration(long elapsedNanos) {
        safelyRecord(() -> waitDuration.record(elapsedNanos, TimeUnit.NANOSECONDS));
    }

    private void safelyRecord(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("event=metrics_recording_failed component=s3_url_cache exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private ImageStorageException unavailable() {
        return new ImageStorageException("S3 이미지 주소 갱신을 완료할 수 없습니다.");
    }

    @Override
    public synchronized void close() {
        closed = true;
        executor.shutdownNow();
        refreshes.values().forEach(result -> result.completeExceptionally(unavailable()));
        refreshes.clear();
        retryAfter.clear();
        entries.invalidateAll();
    }
}
