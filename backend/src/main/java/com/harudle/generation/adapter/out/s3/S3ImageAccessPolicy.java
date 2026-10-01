package com.harudle.generation.adapter.out.s3;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.harudle.generation.config.S3StorageProperties;
import java.util.Arrays;

final class S3ImageAccessPolicy {

    private static final int MAX_OBJECT_KEY_BYTES = 1024;

    private final String generatedRoot;
    private final String referenceRoot;

    S3ImageAccessPolicy(S3StorageProperties properties) {
        if (!properties.isEnvironmentMatched()) {
            throw new IllegalArgumentException("S3 이미지 경로와 실행 환경이 일치해야 합니다.");
        }

        this.generatedRoot = properties.generatedPrefix() + "/";
        this.referenceRoot = properties.referencePrefix() + "/";
    }

    void requireReadable(String objectKey) {
        requireValidKey(objectKey);

        if (!objectKey.startsWith(generatedRoot) && !objectKey.startsWith(referenceRoot)) {
            throw new IllegalArgumentException("현재 환경의 이미지만 조회하거나 복구할 수 있습니다.");
        }
    }

    void requireGenerated(String objectKey) {
        requireValidKey(objectKey);

        if (!objectKey.startsWith(generatedRoot)) {
            throw new IllegalArgumentException("현재 환경의 생성 이미지만 저장, 삭제하거나 URL을 발급할 수 있습니다.");
        }
    }

    private void requireValidKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("이미지 Object Key가 필요합니다.");
        }

        if (objectKey.getBytes(UTF_8).length > MAX_OBJECT_KEY_BYTES) {
            throw new IllegalArgumentException("이미지 Object Key는 UTF-8 기준 1,024바이트 이하여야 합니다.");
        }

        if (objectKey.contains("\\") || objectKey.contains("%")
                || objectKey.chars().anyMatch(Character::isISOControl)
                || Arrays.stream(objectKey.split("/", -1)).anyMatch(this::isInvalidSegment)) {
            throw new IllegalArgumentException("이미지 Object Key에 알 수 없는 경로를 사용할 수 없습니다.");
        }
    }

    private boolean isInvalidSegment(String segment) {
        return segment.isBlank() || segment.equals(".") || segment.equals("..");
    }
}
