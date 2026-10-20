package com.harudle.auth.application;

public final class ProfileImageStorageException extends RuntimeException {

    private final String objectKey;

    public ProfileImageStorageException(String message, String objectKey, Throwable cause) {
        super(message, cause);
        this.objectKey = objectKey;
    }

    public String getObjectKey() {
        return objectKey;
    }
}
