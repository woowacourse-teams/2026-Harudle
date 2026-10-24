package com.harudle.generation.diary.service.port;

import java.io.Serial;
import java.util.Objects;

public final class BackupStorageException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final FailureType failureType;

    public BackupStorageException(FailureType failureType, Throwable cause) {
        super("이미지 백업 저장소 작업에 실패했습니다.", cause);
        this.failureType = Objects.requireNonNull(failureType);
    }

    public FailureType failureType() {
        return failureType;
    }

    public enum FailureType {
        REQUEST_VALIDATION_ERROR,
        REQUEST_PREPARATION_ERROR,
        RESPONSE_PROCESSING_ERROR,
        AUTHENTICATION_ERROR,
        AUTHORIZATION_ERROR,
        CONFIGURATION_ERROR,
        PROVIDER_ERROR,
        CLIENT_ERROR
    }
}
