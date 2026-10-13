package com.harudle.generation.adapter.out.qwen;

import java.io.Serial;

public final class QwenChatException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Kind kind;
    private final Integer statusCode;

    public QwenChatException(Kind kind, Integer statusCode) {
        super("Qwen chat failure: " + kind + (statusCode == null ? "" : " (HTTP " + statusCode + ")"));
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
        HTTP,
        TIMEOUT,
        NETWORK,
        RESPONSE,
        TRUNCATED
    }
}
