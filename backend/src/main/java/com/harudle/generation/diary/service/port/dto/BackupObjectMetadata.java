package com.harudle.generation.diary.service.port.dto;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;

public record BackupObjectMetadata(
        String objectKey,
        MediaType mediaType,
        long size,
        @Nullable String etag
) {

    public BackupObjectMetadata {
        Objects.requireNonNull(objectKey, "백업 Object Key가 필요합니다.");
        Objects.requireNonNull(mediaType, "백업 MIME이 필요합니다.");
        if (objectKey.isBlank() || size <= 0) {
            throw new IllegalArgumentException("백업 Object Key와 양수 크기가 필요합니다.");
        }
    }
}
