package com.harudle.common.logging;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public record ExternalApiResponseDiagnostics(
        @Nullable String finishReason,
        @Nullable Integer candidateTokenCount,
        @Nullable Integer thoughtTokenCount,
        int maxOutputTokens,
        @Nullable Integer responseLength
) {
}
