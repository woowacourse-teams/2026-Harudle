package com.harudle.common.logging;

import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public record ExternalApiFailure(
        String provider,
        String operation,
        String failureType,
        @Nullable String providerStatus,
        @Nullable String providerCode,
        @Nullable String providerRequestId
) {

    public ExternalApiFailure {
        Objects.requireNonNull(provider, "외부 API provider는 필수입니다.");
        Objects.requireNonNull(operation, "외부 API operation은 필수입니다.");
        Objects.requireNonNull(failureType, "외부 API failureType은 필수입니다.");
    }
}
