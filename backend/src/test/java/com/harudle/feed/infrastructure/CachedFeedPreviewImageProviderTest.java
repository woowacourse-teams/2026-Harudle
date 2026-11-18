package com.harudle.feed.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeast;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.feed.service.port.FeedPreviewImageProvider;
import com.harudle.feed.service.exception.FeedPreviewCapacityExceededException;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

class CachedFeedPreviewImageProviderTest {

    private final FeedPreviewImageProvider delegate = mock(FeedPreviewImageProvider.class);
    private final AtomicLong now = new AtomicLong();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private CachedFeedPreviewImageProvider images;

    @BeforeEach
    void setUp() {
        images = new CachedFeedPreviewImageProvider(delegate, now::get, registry);
    }

    @Test
    @DisplayName("HEAD 이후 GET처럼 같은 키를 다시 요청하면 완성된 JPEG를 재사용한다")
    void reusesCompletedJpegForRepeatedRequests() throws Exception {
        when(delegate.provide("comic")).thenReturn(jpeg(new byte[] {1, 2, 3}));

        assertThat(images.provide("comic").resource().getContentAsByteArray()).containsExactly(1, 2, 3);
        assertThat(images.provide("comic").resource().getContentAsByteArray()).containsExactly(1, 2, 3);

        verify(delegate).provide("comic");
    }

    @Test
    @DisplayName("접근이 계속되어도 저장 후 1분이 지나면 다시 읽고 변환한다")
    void expiresFromWriteEvenWhenRepeatedlyAccessed() throws Exception {
        when(delegate.provide("comic")).thenReturn(jpeg(new byte[] {1}), jpeg(new byte[] {2}));
        images.provide("comic");
        now.addAndGet(Duration.ofSeconds(59).toNanos());
        assertThat(images.provide("comic").resource().getContentAsByteArray()).containsExactly(1);

        now.addAndGet(Duration.ofSeconds(1).toNanos());

        assertThat(images.provide("comic").resource().getContentAsByteArray()).containsExactly(2);
        verify(delegate, times(2)).provide("comic");
    }

    @Test
    @DisplayName("실패한 결과는 캐시하지 않고 다음 요청에서 다시 시도한다")
    void doesNotCacheFailuresOrRetainTheirCapacity() throws Exception {
        when(delegate.provide("comic")).thenThrow(new ImageStorageException("실패"))
                .thenThrow(new ImageStorageException("실패"))
                .thenReturn(jpeg(new byte[] {1}));

        assertThatThrownBy(() -> images.provide("comic")).isInstanceOf(ImageStorageException.class);
        assertThatThrownBy(() -> images.provide("comic")).isInstanceOf(ImageStorageException.class);
        assertThat(images.provide("comic").resource().getContentAsByteArray()).containsExactly(1);
    }

    @Test
    @DisplayName("두 변환이 진행 중이어도 완성된 이미지는 제공하고 세 번째 변환은 즉시 거절한다")
    void keepsCachedImagesAvailableWhileLimitingUncachedWork() throws Exception {
        when(delegate.provide("cached")).thenReturn(jpeg(new byte[] {1}));
        images.provide("cached");
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch finish = new CountDownLatch(1);
        for (String key : new String[] {"first", "second"}) {
            when(delegate.provide(key)).thenAnswer(invocation -> {
                entered.countDown();
                if (!finish.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("변환 종료를 기다리지 못했습니다.");
                }
                return jpeg(new byte[] {2});
            });
        }
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> images.provide("first"));
            var second = executor.submit(() -> images.provide("second"));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(images.provide("cached").resource().getContentAsByteArray()).containsExactly(1);
                assertThatThrownBy(() -> images.provide("third"))
                        .isInstanceOf(FeedPreviewCapacityExceededException.class);
                assertThat(registry.counter("harudle.feed.preview.capacity.rejected").count()).isEqualTo(1);
            } finally {
                finish.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS).mediaType()).isEqualTo(MediaType.IMAGE_JPEG);
            assertThat(second.get(5, TimeUnit.SECONDS).mediaType()).isEqualTo(MediaType.IMAGE_JPEG);
        }
        when(delegate.provide("third")).thenReturn(jpeg(new byte[] {3}));
        assertThat(images.provide("third").resource().getContentAsByteArray()).containsExactly(3);
    }

    @Test
    @DisplayName("다른 키들의 JPEG 합계가 16MiB를 넘으면 일부 결과를 보관하지 않는다")
    void boundsCachedJpegBytesInsteadOfOnlyEntryCount() {
        for (String key : new String[] {"first", "second", "third"}) {
            when(delegate.provide(key)).thenAnswer(invocation -> jpeg(new byte[6 * 1024 * 1024]));
            images.provide(key);
        }

        images.provide("first");
        images.provide("second");
        images.provide("third");

        // 18MiB는 전부 보관할 수 없으므로 적어도 하나는 원본 제공자를 다시 호출한다.
        verify(delegate, atLeast(4)).provide(anyString());
    }

    private static ReferenceImage jpeg(byte[] bytes) {
        return new ReferenceImage(new ByteArrayResource(bytes), MediaType.IMAGE_JPEG);
    }
}
