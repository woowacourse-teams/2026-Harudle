package com.harudle.feed.service.exception;

import java.io.Serial;

public final class FeedIntegrationUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public FeedIntegrationUnavailableException(Class<?> collaborator) {
        super("피드 연동 구현체가 구성되지 않았습니다: " + collaborator.getSimpleName());
    }
}
