package com.harudle.generation.adapter.out.openai;

/** Fixed diagnostic values; never derived from the response body. */
enum OpenAiResponseFailureReason {
    INVALID_CONTENT_TYPE,
    RESPONSE_TOO_LARGE,
    INVALID_JSON,
    IMAGE_MISSING,
    INVALID_BASE64,
    INVALID_PNG,
    UNEXPECTED_RESPONSE_ERROR
}
