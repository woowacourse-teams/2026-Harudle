package com.harudle.generation.adapter.out.gemini;

import com.google.genai.errors.ApiException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
record GeminiProviderErrorMetadata(
        @Nullable String status,
        @Nullable String code
) {

    private static final int MAX_CAUSE_DEPTH = 16;

    static GeminiProviderErrorMetadata from(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof ApiException apiException) {
                return new GeminiProviderErrorMetadata(
                        apiException.status(),
                        Integer.toString(apiException.code())
                );
            }
            current = current.getCause();
        }
        return new GeminiProviderErrorMetadata(null, null);
    }
}
