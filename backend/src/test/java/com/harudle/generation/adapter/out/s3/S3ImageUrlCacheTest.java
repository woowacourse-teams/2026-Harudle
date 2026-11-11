package com.harudle.generation.adapter.out.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.harudle.generation.config.S3UrlCacheProperties;
import com.harudle.generation.diary.service.port.ImageLookupBudget;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.ImageStorageException.DiagnosticType;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(10)
class S3ImageUrlCacheTest {

    private static final String KEY = "harudle/generated/diary-images/dev/diary/image.webp";
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-11T03:00:00Z"));
    private final Clock clock = mock(Clock.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ExecutorService callers = Executors.newFixedThreadPool(8);
    private S3ImageUrlCache cache;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenAnswer(ignored -> now.get());
        cache = new S3ImageUrlCache(properties(500, 4, 64, Duration.ofSeconds(2)), clock, registry);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        cache.close();
        callers.shutdownNow();
        assertThat(callers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        registry.close();
    }

    @Test
    @DisplayName("재사용 가능한 S3 주소는 다시 서명하지 않는다")
    void reuseSignedUrlBeforeRefreshMargin() {
        AtomicInteger signatures = new AtomicInteger();
        Supplier<SignedS3ImageUrl> signer = () -> {
            signatures.incrementAndGet();
            return signedUrl("first", 900, true);
        };

        ImageAccessUrl first = accessUrlFor(KEY, signer);
        now.updateAndGet(value -> value.plusSeconds(834));
        ImageAccessUrl second = accessUrlFor(KEY, signer);

        assertThat(second).isEqualTo(first);
        assertThat(signatures).hasValue(1);
        assertThat(eventCount("hit")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {835, 840, 900})
    @DisplayName("잔여 1분과 시계 여유 5초 경계부터 새 주소를 발급한다")
    void refreshAtRemainingLifetimeBoundary(int elapsedSeconds) {
        ImageAccessUrl first = accessUrlFor(KEY, () -> signedUrl("first", 900, true));
        now.updateAndGet(value -> value.plusSeconds(elapsedSeconds));

        ImageAccessUrl refreshed = accessUrlFor(KEY, () -> signedUrl("second", 900, true));

        assertThat(refreshed.url()).isNotEqualTo(first.url());
        assertThat(accessUrlFor(KEY, () -> { throw new AssertionError("캐시 적중이어야 합니다."); }))
                .isEqualTo(refreshed);
    }

    @Test
    @DisplayName("자격 만료를 알 수 없으면 성공한 주소를 반환하되 저장하지 않는다")
    void bypassCacheForUnknownCredentialExpiration() {
        AtomicInteger signatures = new AtomicInteger();
        Supplier<SignedS3ImageUrl> signer = () -> signedUrl("unknown-" + signatures.incrementAndGet(), 900, false);

        ImageAccessUrl first = accessUrlFor(KEY, signer);
        ImageAccessUrl second = accessUrlFor(KEY, signer);

        assertThat(first.url()).isNotEqualTo(second.url());
        assertThat(signatures).hasValue(2);
        assertThat(eventCount("bypass_unknown_expiration")).isEqualTo(2);
        assertThat(registry.get("harudle.s3.url.cache.size").gauge().value()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 5, 60, 65})
    @DisplayName("알려진 만료가 너무 가까운 새 주소를 정상 결과로 반환하지 않는다")
    void rejectNewUrlWithoutSufficientLifetime(int remainingSeconds) {
        assertThatThrownBy(() -> accessUrlFor(KEY, () -> signedUrl("short", remainingSeconds, true)))
                .isInstanceOf(ImageStorageException.class);
        assertThat(eventCount("refresh_failure")).isEqualTo(1);
    }

    @Test
    @DisplayName("갱신 실패 후 5초 동안은 유효한 이전 주소만 사용하고 다시 시도한다")
    void reuseValidPreviousUrlDuringFailureCooldown() {
        ImageAccessUrl previous = accessUrlFor(KEY, () -> signedUrl("previous", 900, true));
        now.updateAndGet(value -> value.plusSeconds(840));
        AtomicInteger failures = new AtomicInteger();
        Supplier<SignedS3ImageUrl> failingSigner = () -> {
            failures.incrementAndGet();
            throw new ImageStorageException("서명 실패");
        };

        assertThat(accessUrlFor(KEY, failingSigner)).isEqualTo(previous);
        now.updateAndGet(value -> value.plusSeconds(4));
        assertThat(accessUrlFor(KEY, failingSigner)).isEqualTo(previous);
        assertThat(failures).hasValue(1);
        now.updateAndGet(value -> value.plusSeconds(1));
        assertThat(accessUrlFor(KEY, failingSigner)).isEqualTo(previous);
        assertThat(failures).hasValue(2);
    }

    @Test
    @DisplayName("실패 대기 중에도 이전 주소가 만료되면 반환하지 않는다")
    void neverReturnExpiredPreviousUrlDuringCooldown() {
        accessUrlFor(KEY, () -> signedUrl("previous", 900, true));
        now.updateAndGet(value -> value.plusSeconds(894));
        Supplier<SignedS3ImageUrl> failingSigner = () -> { throw new ImageStorageException("서명 실패"); };
        accessUrlFor(KEY, failingSigner);
        now.updateAndGet(value -> value.plusSeconds(1));

        assertThatThrownBy(() -> accessUrlFor(KEY, failingSigner))
                .isInstanceOf(ImageStorageException.class);
        assertThat(eventCount("refresh_failure")).isEqualTo(1);
    }

    @Test
    @DisplayName("빈 캐시의 실제 발급 실패도 5초간 재시도를 막는다")
    void suppressRepeatedFailuresWithoutPreviousUrl() {
        AtomicInteger signatures = new AtomicInteger();
        Supplier<SignedS3ImageUrl> signer = () -> {
            signatures.incrementAndGet();
            throw new ImageStorageException("서명 실패");
        };
        assertThatThrownBy(() -> accessUrlFor(KEY, signer)).isInstanceOf(ImageStorageException.class);
        now.updateAndGet(value -> value.plusSeconds(4));
        assertThatThrownBy(() -> accessUrlFor(KEY, signer))
                .isInstanceOfSatisfying(ImageStorageException.class, failure ->
                        assertThat(failure.diagnosticType()).isEqualTo(DiagnosticType.CACHE_COOLDOWN));
        assertThat(signatures).hasValue(1);
        now.updateAndGet(value -> value.plusSeconds(1));

        ImageAccessUrl recovered = accessUrlFor(KEY, () -> signedUrl("recovered", 900, true));

        assertThat(recovered.url().getPath()).isEqualTo("/recovered");
    }

    @Test
    @DisplayName("동일 이미지의 지연된 발급을 여러 요청이 공유하고 다른 이미지는 진행한다")
    void coalesceConcurrentRefreshesWithoutBlockingOtherImages() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger signatures = new AtomicInteger();
        Supplier<SignedS3ImageUrl> signer = () -> {
            signatures.incrementAndGet();
            started.countDown();
            awaitRelease(release);
            return signedUrl("shared", 900, true);
        };
        try {
            Future<ImageAccessUrl> first = callers.submit(() -> accessUrlFor(KEY, signer));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            List<Future<ImageAccessUrl>> waiting = IntStream.range(0, 4)
                    .mapToObj(ignored -> callers.submit(() -> accessUrlFor(KEY, signer))).toList();
            awaitEvent("coalesced", 4);
            assertThat(accessUrlFor(KEY + "-other", () -> signedUrl("other", 900, true)).url().getPath())
                    .isEqualTo("/other");
            release.countDown();

            ImageAccessUrl shared = first.get(2, TimeUnit.SECONDS);
            for (Future<ImageAccessUrl> result : waiting) {
                assertThat(result.get(2, TimeUnit.SECONDS)).isEqualTo(shared);
            }
            assertThat(signatures).hasValue(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("대기 제한은 실제 작업을 취소하거나 실패 대기를 시작하지 않는다")
    void keepSingleRefreshAfterWaiterTimeoutAndRecoverOnCompletion() throws Exception {
        replaceCache(properties(500, 2, 8, Duration.ofMillis(150)));
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger signatures = new AtomicInteger();
        Supplier<SignedS3ImageUrl> delayed = () -> {
            signatures.incrementAndGet();
            awaitRelease(release);
            return signedUrl("late", 900, true);
        };
        try {
            assertThatThrownBy(() -> accessUrlFor(KEY, delayed))
                    .isInstanceOfSatisfying(ImageStorageException.class, failure ->
                            assertThat(failure.diagnosticType()).isEqualTo(DiagnosticType.CACHE_WAIT_TIMEOUT));
            assertThatThrownBy(() -> accessUrlFor(KEY, delayed)).isInstanceOf(ImageStorageException.class);
            assertThat(signatures).hasValue(1);
            assertThat(eventCount("refresh_failure")).isZero();
            Future<ImageAccessUrl> waiting = callers.submit(() -> accessUrlFor(KEY, delayed));
            awaitEvent("coalesced", 2);
            release.countDown();
            ImageAccessUrl completed = waiting.get(2, TimeUnit.SECONDS);
            assertThat(accessUrlFor(KEY, delayed)).isEqualTo(completed);
            assertThat(signatures).hasValue(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("지연 중 유효한 이전 주소만 반환하고 만료 여유를 넘으면 오류를 반환한다")
    void reusePreviousUrlOnlyWhileItRemainsValidDuringTimeout() {
        replaceCache(properties(500, 2, 8, Duration.ofMillis(150)));
        ImageAccessUrl previous = accessUrlFor(KEY, () -> signedUrl("previous", 900, true));
        now.updateAndGet(value -> value.plusSeconds(840));
        CountDownLatch release = new CountDownLatch(1);
        Supplier<SignedS3ImageUrl> delayed = () -> {
            awaitRelease(release);
            return signedUrl("refreshed", 900, true);
        };
        try {
            assertThat(accessUrlFor(KEY, delayed)).isEqualTo(previous);
            now.updateAndGet(value -> value.plusSeconds(55));

            assertThatThrownBy(() -> accessUrlFor(KEY, delayed)).isInstanceOf(ImageStorageException.class);
            assertThat(eventCount("wait_timeout")).isEqualTo(2);
            assertThat(eventCount("refresh_failure")).isZero();
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("멈춘 작업이 실행 자원을 차지해도 추가 작업과 실패 상태가 무한히 쌓이지 않는다")
    void boundActualRefreshTasksWhenSigningDoesNotStop() {
        replaceCache(properties(500, 1, 8, Duration.ofMillis(20)));
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger signatures = new AtomicInteger();
        Supplier<SignedS3ImageUrl> stuck = () -> {
            signatures.incrementAndGet();
            awaitRelease(release);
            return signedUrl("stuck", 900, true);
        };
        try {
            assertThatThrownBy(() -> accessUrlFor(KEY, stuck)).isInstanceOf(ImageStorageException.class);
            IntStream.range(0, 8).forEach(index -> assertThatThrownBy(
                    () -> accessUrlFor(KEY + index, stuck)).isInstanceOf(ImageStorageException.class));
            IntStream.range(8, 20).forEach(index -> assertThatThrownBy(() -> accessUrlFor(KEY + index, stuck))
                    .isInstanceOfSatisfying(ImageStorageException.class, failure ->
                            assertThat(failure.diagnosticType()).isEqualTo(DiagnosticType.CACHE_REFRESH_REJECTED)));
            assertThat(signatures).hasValue(1);
            assertThat(eventCount("refresh_rejected")).isEqualTo(12);
            assertThat(eventCount("refresh_failure")).isZero();
            assertThat(registry.get("harudle.s3.url.cache.refresh.active").gauge().value()).isEqualTo(1);
            assertThat(registry.get("harudle.s3.url.cache.refresh.queued").gauge().value()).isEqualTo(8);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("오래 대기한 작업은 서명 없이 건너뛰고 같은 키를 다시 발급할 수 있다")
    void skipAnExpiredQueuedRefreshWithoutStartingAFailureCooldown() throws Exception {
        AtomicLong elapsed = new AtomicLong();
        cache.close();
        registry.clear();
        cache = new S3ImageUrlCache(properties(500, 1, 8, Duration.ofSeconds(2)), clock, registry, elapsed::get);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String queuedKey = KEY + "-queued";
        AtomicInteger queuedSignatures = new AtomicInteger();
        Supplier<SignedS3ImageUrl> queuedSigner = () -> {
            queuedSignatures.incrementAndGet();
            return signedUrl("queued", 900, true);
        };
        try {
            Future<ImageAccessUrl> active = callers.submit(() -> accessUrlFor(KEY, () -> {
                started.countDown();
                awaitRelease(release);
                return signedUrl("active", 900, true);
            }));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            ImageLookupBudget requestBudget = new ImageLookupBudget(Duration.ofMillis(200), System::nanoTime);
            assertThatThrownBy(() -> cache.accessUrlFor(queuedKey, queuedSigner, requestBudget))
                    .isInstanceOf(ImageStorageException.class);
            assertThat(registry.get("harudle.s3.url.cache.refresh.queued").gauge().value()).isEqualTo(1);
            elapsed.addAndGet(Duration.ofSeconds(2).toNanos());
            release.countDown();

            assertThat(active.get(2, TimeUnit.SECONDS).url().getPath()).isEqualTo("/active");
            accessUrlFor(KEY + "-next", () -> signedUrl("next", 900, true));
            assertThat(eventCount("refresh_queue_expired")).isEqualTo(1);
            assertThat(eventCount("refresh_failure")).isZero();
            assertThat(queuedSignatures).hasValue(0);
            assertThat(accessUrlFor(queuedKey, queuedSigner).url().getPath()).isEqualTo("/queued");
            assertThat(queuedSignatures).hasValue(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("동시에 기다릴 수 있는 요청 수도 제한한다")
    void boundWaitingRequestsSeparatelyFromRunningTasks() throws Exception {
        replaceCache(properties(500, 1, 1, Duration.ofSeconds(2)));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<ImageAccessUrl> first = callers.submit(() -> accessUrlFor(KEY, () -> {
                started.countDown();
                awaitRelease(release);
                return signedUrl("first", 900, true);
            }));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> accessUrlFor(KEY, () -> signedUrl("unexpected", 900, true)))
                    .isInstanceOf(ImageStorageException.class);
            assertThat(eventCount("wait_rejected")).isEqualTo(1);
            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS).url().getPath()).isEqualTo("/first");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("Caffeine의 기본 제거 정책으로 최대 항목 수를 유지한다")
    void evictEntriesUsingCaffeineCapacityPolicy() throws Exception {
        IntStream.range(0, 600).forEach(index ->
                accessUrlFor(KEY + index, () -> signedUrl("image", 900, true)));
        awaitCount("harudle.s3.url.cache.evictions", "cause", "SIZE", 100);

        assertThat(registry.get("harudle.s3.url.cache.size").gauge().value()).isLessThanOrEqualTo(500);
        assertThat(registry.get("harudle.s3.url.cache.evictions").tag("cause", "SIZE").counter().count())
                .isGreaterThanOrEqualTo(100);
    }

    @Test
    @DisplayName("실패 상태가 가득 차도 5초 제한을 잃지 않고 공간이 생기면 다시 서명한다")
    void protectFailureCooldownsWhenRetryCapacityIsFull() {
        replaceCache(properties(2, 2, 8, Duration.ofSeconds(2)));
        Supplier<SignedS3ImageUrl> failure = () -> { throw new ImageStorageException("서명 실패"); };
        assertThatThrownBy(() -> accessUrlFor(KEY, failure)).isInstanceOf(ImageStorageException.class);
        assertThatThrownBy(() -> accessUrlFor(KEY + "-second", failure))
                .isInstanceOf(ImageStorageException.class);

        assertThatThrownBy(() -> accessUrlFor(KEY + "-third", () -> signedUrl("new", 900, true)))
                .isInstanceOf(ImageStorageException.class);
        assertThat(eventCount("retry_capacity_rejected")).isEqualTo(1);
        assertThatThrownBy(() -> accessUrlFor(KEY, () -> signedUrl("too-early", 900, true)))
                .isInstanceOf(ImageStorageException.class);
        assertThat(eventCount("cooldown")).isEqualTo(1);
        now.updateAndGet(value -> value.plusSeconds(5));

        assertThat(accessUrlFor(KEY + "-third", () -> signedUrl("new", 900, true)).url().getPath())
                .isEqualTo("/new");
    }

    @Test
    @DisplayName("종료한 캐시는 늦게 끝난 작업으로 다시 채워지지 않는다")
    void doNotPublishLateResultsAfterClosingCache() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> waiting = callers.submit(() -> assertThatThrownBy(() -> accessUrlFor(KEY, () -> {
                started.countDown();
                awaitRelease(release);
                return signedUrl("late", 900, true);
            })).isInstanceOf(ImageStorageException.class));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            cache.close();
            waiting.get(2, TimeUnit.SECONDS);
            release.countDown();
            assertThat(registry.get("harudle.s3.url.cache.size").gauge().value()).isZero();
            assertThatThrownBy(() -> accessUrlFor(KEY, () -> signedUrl("new", 900, true)))
                    .isInstanceOf(ImageStorageException.class);
        } finally {
            release.countDown();
        }
    }

    private ImageAccessUrl accessUrlFor(String key, Supplier<SignedS3ImageUrl> signer) {
        return cache.accessUrlFor(key, signer, cache.newLookupBudget());
    }

    private SignedS3ImageUrl signedUrl(String name, int lifetimeSeconds, boolean expirationKnown) {
        return new SignedS3ImageUrl(new ImageAccessUrl(URI.create("https://example.com/" + name),
                now.get().plusSeconds(lifetimeSeconds)), expirationKnown);
    }

    private S3UrlCacheProperties properties(int size, int workers, int waiters, Duration timeout) {
        return new S3UrlCacheProperties(true, size, workers, waiters, timeout);
    }

    private void replaceCache(S3UrlCacheProperties properties) {
        cache.close();
        registry.clear();
        cache = new S3ImageUrlCache(properties, clock, registry);
    }

    private double eventCount(String result) {
        return count("harudle.s3.url.cache.events", "result", result);
    }

    private double count(String name, String tag, String value) {
        var counter = registry.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    private void awaitEvent(String result, int count) throws InterruptedException {
        awaitCount("harudle.s3.url.cache.events", "result", result, count);
    }

    private void awaitCount(String name, String tag, String value, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (count(name, tag, value) < expected && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertThat(count(name, tag, value)).isGreaterThanOrEqualTo(expected);
    }

    private void awaitRelease(CountDownLatch release) {
        boolean interrupted = false;
        while (release.getCount() != 0) {
            try {
                release.await();
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
