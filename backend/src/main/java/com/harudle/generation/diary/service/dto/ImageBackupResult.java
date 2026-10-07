package com.harudle.generation.diary.service.dto;

import com.harudle.generation.diary.service.port.dto.BackupUploadResult;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.MediaType;

/** S3와 R2의 MIME, 크기, SHA-256 일치를 확인한 백업 결과. */
public record ImageBackupResult(
        String originalKey,
        BackupUploadResult uploadResult,
        MediaType mediaType,
        long size,
        String sha256,
        Instant verifiedAt
) {

    public ImageBackupResult {
        Objects.requireNonNull(originalKey);
        Objects.requireNonNull(uploadResult);
        Objects.requireNonNull(mediaType);
        Objects.requireNonNull(sha256);
        Objects.requireNonNull(verifiedAt);
        if (originalKey.isBlank() || size <= 0 || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("유효한 검증 완료 백업 결과가 필요합니다.");
        }
    }
}
