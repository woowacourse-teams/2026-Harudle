package com.harudle.auth.application;

import org.jspecify.annotations.Nullable;

public final class ProfileImageStorageException extends RuntimeException {

    private final @Nullable String objectKey;

    public ProfileImageStorageException(String message, @Nullable String objectKey, @Nullable Throwable cause) {
        super(message, cause);
        this.objectKey = objectKey;
    }

    public @Nullable String getObjectKey() {
        return objectKey;
    }
}
