package com.harudle.generation.service.dto;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.harudle.generation.domain.GenerationStatus;

public record ComicGenerationResult(
        UUID generationId,
        GenerationStatus status,
        String title,
        String imageObjectKey,
        Instant completedAt,
        boolean newlyCreated
) {

    private static final int MAX_IMAGE_OBJECT_KEY_BYTES = 1024;

    public ComicGenerationResult {
        validateGenerationId(generationId);
        validateStatus(status);
        title = normalizeTitle(title);
        imageObjectKey = normalizeImageObjectKey(imageObjectKey);
        validateCompletedAt(completedAt);
    }

    private static String normalizeTitle(String title) {
        validateTitle(title);
        return title.strip();
    }

    private static String normalizeImageObjectKey(String imageObjectKey) {
        validateImageObjectKey(imageObjectKey);
        return imageObjectKey.strip();
    }

    private static void validateGenerationId(UUID generationId) {
        if (generationId == null) {
            throw new IllegalArgumentException("생성 ID는 필수입니다.");
        }
    }

    private static void validateStatus(GenerationStatus status) {
        if (status != GenerationStatus.SUCCEEDED) {
            throw new IllegalArgumentException("성공한 생성 작업만 결과로 반환할 수 있습니다.");
        }
    }

    private static void validateTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("생성 결과 제목은 필수입니다.");
        }
    }

    private static void validateImageObjectKey(String imageObjectKey) {
        if (imageObjectKey == null || imageObjectKey.isBlank()) {
            throw new IllegalArgumentException("생성 이미지 Object Key는 필수입니다.");
        }
        int byteLength = imageObjectKey.strip().getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > MAX_IMAGE_OBJECT_KEY_BYTES) {
            throw new IllegalArgumentException("생성 이미지 Object Key는 UTF-8 기준 1,024바이트 이하여야 합니다.");
        }
    }

    private static void validateCompletedAt(Instant completedAt) {
        if (completedAt == null) {
            throw new IllegalArgumentException("생성 완료 시각은 필수입니다.");
        }
    }
}
