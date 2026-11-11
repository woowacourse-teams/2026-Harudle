package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import com.harudle.observability.RequestOperationTimings;
import io.micrometer.core.instrument.MeterRegistry;

public final class CachedS3ImageUrlProvider implements ImageUrlProvider {

    private final S3ImageUrlProvider signer;
    private final S3ImageUrlCache cache;
    private final S3SigningMetrics metrics;
    private final ImageUrlProvider uncached;

    public CachedS3ImageUrlProvider(S3ImageUrlProvider signer, S3ImageUrlCache cache, MeterRegistry meterRegistry) {
        this.signer = signer;
        this.cache = cache;
        this.metrics = new S3SigningMetrics(meterRegistry);
        this.uncached = new ObservedImageUrlProvider(signer, metrics);
    }

    @Override
    public ImageAccessUrl createAccessUrl(String imageObjectKey) {
        return uncached.createAccessUrl(imageObjectKey);
    }

    @Override
    public ImageUrlProvider forFeedRead() {
        return this::createCachedUrl;
    }

    private ImageAccessUrl createCachedUrl(String imageObjectKey) {
        signer.validateObjectKey(imageObjectKey);
        return cache.accessUrlFor(imageObjectKey,
                RequestOperationTimings.measureOnCurrentRequest(RequestOperationTimings.Operation.S3_SIGN,
                        () -> metrics.measure(() -> signer.sign(imageObjectKey))));
    }
}
