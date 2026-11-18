package com.harudle.generation.adapter.out.openai;

import java.io.Serial;

public final class OpenAiChatException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Kind kind;
    private final Integer statusCode;

    public OpenAiChatException(Kind kind, Integer statusCode) {
        super("OpenAI chat failure: " + kind + (statusCode == null ? "" : " (HTTP " + statusCode + ")"));
        this.kind = kind;
        this.statusCode = statusCode;
    }

    public Kind kind() {
        return kind;
    }

    public Integer statusCode() {
        return statusCode;
    }

    public enum Kind {
        CONTENT_BLOCKED,
        HTTP,
        TIMEOUT,
        NETWORK,
        RESPONSE,
        TRUNCATED
    }
}
