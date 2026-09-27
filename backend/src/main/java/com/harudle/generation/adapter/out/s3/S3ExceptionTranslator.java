package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.diary.service.port.ImageStorageException;

public final class S3ExceptionTranslator {

    private static final int MAX_OBJECT_KEY_LENGTH_IN_MESSAGE = 120;

    public ImageStorageException translate(String operation, String objectKey, Throwable cause) {
        String message = "S3 이미지 " + operation + "에 실패했습니다.";
        if (objectKey != null && !objectKey.isBlank()) {
            message = message + " Object Key: " + abbreviate(objectKey);
        }
        return new ImageStorageException(message, cause);
    }

    private static String abbreviate(String objectKey) {
        String safeObjectKey = objectKey.replaceAll("\\p{Cntrl}", "?");
        if (safeObjectKey.length() <= MAX_OBJECT_KEY_LENGTH_IN_MESSAGE) {
            return safeObjectKey;
        }
        return safeObjectKey.substring(0, MAX_OBJECT_KEY_LENGTH_IN_MESSAGE) + "...";
    }
}
