package com.harudle.feed.infrastructure;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.harudle.feed.service.exception.FeedPreviewCapacityExceededException;
import com.harudle.feed.service.port.FeedPreviewImageProvider;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** 공개 여부는 호출 서비스가 매번 확인한다. 이 캐시는 확인된 키의 JPEG 바이트만 보관한다. */
@Primary
@Component
public final class CachedFeedPreviewImageProvider implements FeedPreviewImageProvider {

    private static final int MAXIMUM_BYTES = 16 * 1024 * 1024;
    private static final Duration LIFETIME = Duration.ofMinutes(1);

    private final FeedPreviewImageProvider delegate;
    private final Cache<String, byte[]> entries;
    private final Counter rejected;
    private final Semaphore imageRequests = new Semaphore(2);

    @Autowired
    public CachedFeedPreviewImageProvider(StoredFeedPreviewImageProvider delegate, MeterRegistry registry) {
        this(delegate, Ticker.systemTicker(), registry);
    }

    CachedFeedPreviewImageProvider(FeedPreviewImageProvider delegate, Ticker ticker, MeterRegistry registry) {
        this.delegate = delegate;
        this.rejected = registry.counter("harudle.feed.preview.capacity.rejected");
        this.entries = Caffeine.newBuilder()
                .maximumWeight(MAXIMUM_BYTES)
                .weigher((String key, byte[] jpeg) -> jpeg.length)
                .expireAfterWrite(LIFETIME)
                .ticker(ticker)
                .executor(Runnable::run)
                .build();
    }

    @Override
    public ReferenceImage provide(String imageObjectKey) {
        byte[] cached = entries.getIfPresent(imageObjectKey);
        if (cached != null) {
            return jpeg(cached);
        }
        if (!imageRequests.tryAcquire()) {
            rejected.increment();
            throw new FeedPreviewCapacityExceededException();
        }
        try {
            return load(imageObjectKey);
        } finally {
            imageRequests.release();
        }
    }

    private ReferenceImage load(String key) {
        // 다른 요청이 먼저 완성했다면 다운로드와 변환을 반복하지 않는다.
        byte[] cached = entries.getIfPresent(key);
        if (cached != null) {
            return jpeg(cached);
        }
        ReferenceImage rendered = delegate.provide(key);
        try (InputStream input = rendered.resource().getInputStream()) {
            byte[] bytes = input.readNBytes(MAXIMUM_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAXIMUM_BYTES) {
                throw new ImageStorageException("공유 미리보기 JPEG 크기가 제한을 초과했습니다.");
            }
            entries.put(key, bytes);
            return jpeg(bytes);
        } catch (IOException exception) {
            throw new ImageStorageException("공유 미리보기 JPEG를 읽지 못했습니다.");
        }
    }

    private static ReferenceImage jpeg(byte[] bytes) {
        return new ReferenceImage(new ByteArrayResource(bytes), MediaType.IMAGE_JPEG);
    }
}
