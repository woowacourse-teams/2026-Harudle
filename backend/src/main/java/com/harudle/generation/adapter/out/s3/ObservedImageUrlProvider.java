package com.harudle.generation.adapter.out.s3;

import com.harudle.observability.RequestOperationTimings;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;

/** Counts URL signing outcomes; it does not imply that the S3 object exists. */
public final class ObservedImageUrlProvider implements ImageUrlProvider {

    private final ImageUrlProvider delegate;
    private final S3SigningMetrics metrics;

    public ObservedImageUrlProvider(ImageUrlProvider delegate, MeterRegistry meterRegistry) {
        this(delegate, new S3SigningMetrics(meterRegistry));
    }

    ObservedImageUrlProvider(ImageUrlProvider delegate, S3SigningMetrics metrics) {
        this.delegate = Objects.requireNonNull(delegate);
        this.metrics = metrics;
    }

    @Override
    public ImageUrlProvider forRead() {
        return new ObservedImageUrlProvider(delegate.forRead(), metrics);
    }

    @Override
    public ImageUrlProvider forFeedRead() {
        return new ObservedImageUrlProvider(delegate.forFeedRead(), metrics);
    }

    @Override
    public ImageUrlProvider forPublication() {
        return new ObservedImageUrlProvider(delegate.forPublication(), metrics);
    }

    @Override
    public ImageUrlProvider forResponse() {
        return new ObservedImageUrlProvider(delegate.forResponse(), metrics);
    }

    @Override
    public ImageAccessUrl createAccessUrl(String imageObjectKey) {
        return RequestOperationTimings.measure(RequestOperationTimings.Operation.S3_SIGN,
                () -> metrics.measure(() -> delegate.createAccessUrl(imageObjectKey)));
    }
}
