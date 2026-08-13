package com.harudle.generation.adapter.out.s3;

import static java.nio.charset.StandardCharsets.UTF_8;

final class S3ObjectKeyValidator {

    private static final int MAX_OBJECT_KEY_BYTES = 1024;

    private S3ObjectKeyValidator() {
    }

    static void validate(String imageObjectKey) {
        if (imageObjectKey == null || imageObjectKey.isBlank()) {
            throw new IllegalArgumentException("이미지 Object Key가 필요합니다.");
        }
        if (imageObjectKey.getBytes(UTF_8).length > MAX_OBJECT_KEY_BYTES) {
            throw new IllegalArgumentException("이미지 Object Key는 UTF-8 기준 1,024바이트 이하여야 합니다.");
        }
    }
}
