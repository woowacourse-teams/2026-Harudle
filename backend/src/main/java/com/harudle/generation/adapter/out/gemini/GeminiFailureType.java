package com.harudle.generation.adapter.out.gemini;

import com.google.genai.types.Candidate;
import com.google.genai.types.GenerateContentResponse;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import java.util.List;
import java.util.Optional;
import tools.jackson.core.JacksonException;

/** Stable, bounded categories for Gemini logs and metrics. */
enum GeminiFailureType {
    RATE_LIMIT,
    PROVIDER_5XX,
    TIMEOUT,
    AUTH_CONFIGURATION_ERROR,
    REQUEST_REJECTED,
    PROVIDER_ERROR,
    REQUEST_PREPARATION_ERROR,
    INLINE_REQUEST_TOO_LARGE,
    OUTPUT_TOKEN_LIMIT,
    EMPTY_RESPONSE,
    INVALID_JSON,
    SCHEMA_VIOLATION,
    IMAGE_PART_MISSING,
    IMAGE_PART_INVALID,
    RESPONSE_PROCESSING_ERROR;

    static GeminiFailureType provider(Throwable exception, AiGenerationErrorType errorType) {
        if (errorType == AiGenerationErrorType.TIMEOUT) {
            return TIMEOUT;
        }
        GeminiProviderErrorMetadata metadata = GeminiProviderErrorMetadata.from(exception);
        String code = metadata.code();
        if ("429".equals(code)) {
            return RATE_LIMIT;
        }
        if ("401".equals(code) || "403".equals(code)) {
            return AUTH_CONFIGURATION_ERROR;
        }
        if (code != null) {
            try {
                int statusCode = Integer.parseInt(code);
                if (statusCode >= 500 && statusCode < 600) {
                    return PROVIDER_5XX;
                }
                if (statusCode >= 400 && statusCode < 500) {
                    // A generic 400 does not prove that the input context limit was exceeded.
                    return REQUEST_REJECTED;
                }
            } catch (NumberFormatException ignored) {
                // Keep unrecognized provider codes in the bounded fallback category.
            }
        }
        return PROVIDER_ERROR;
    }

    static GeminiFailureType preparation(Throwable cause) {
        return cause instanceof GeminiDiaryImageGenerator.InlineRequestTooLargeException
                ? INLINE_REQUEST_TOO_LARGE : REQUEST_PREPARATION_ERROR;
    }

    static GeminiFailureType storyboardResponse(String finishReason, Throwable cause) {
        if ("MAX_TOKENS".equals(finishReason)) {
            return OUTPUT_TOKEN_LIMIT;
        }
        if (cause instanceof IllegalStateException) {
            return EMPTY_RESPONSE;
        }
        if (cause instanceof JacksonException) {
            return INVALID_JSON;
        }
        if (cause instanceof IllegalArgumentException) {
            return SCHEMA_VIOLATION;
        }
        return RESPONSE_PROCESSING_ERROR;
    }

    static GeminiFailureType imageResponse(GenerateContentResponse response) {
        if ("MAX_TOKENS".equals(finishReason(response))) {
            return OUTPUT_TOKEN_LIMIT;
        }
        if (response == null) {
            return IMAGE_PART_MISSING;
        }
        try {
            List<com.google.genai.types.Part> parts = response.parts();
            if (parts == null || parts.stream().noneMatch(part ->
                    part != null && part.inlineData().isPresent())) {
                return IMAGE_PART_MISSING;
            }
            return IMAGE_PART_INVALID;
        } catch (RuntimeException ignored) {
            return RESPONSE_PROCESSING_ERROR;
        }
    }

    static String finishReason(GenerateContentResponse response) {
        if (response == null) {
            return null;
        }
        try {
            Optional<List<Candidate>> candidates = response.candidates();
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            return candidates.get().stream().findFirst()
                    .flatMap(Candidate::finishReason)
                    .map(Object::toString)
                    .orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
