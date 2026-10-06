package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.config.R2StorageProperties;
import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.domain.ImageVariantKeys;
import com.harudle.generation.diary.service.exception.ImageBackupNotFoundException;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.BackupStorageException;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.ImageStorageException.DiagnosticType;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.generation.diary.service.port.dto.BackupObjectMetadata;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 접근 권한이 확인된 DB 키에 대해 S3를 우선 조회하고, 실패하면 R2 원본 URL을 발급한다. */
public final class R2FallbackImageUrlProvider implements ImageUrlProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(R2FallbackImageUrlProvider.class);
    private final ImageUrlProvider primary;
    private final ImageStorage storage;
    private final BackupObjectStorage backup;
    private final S3StorageProperties s3;
    private final R2StorageProperties r2;
    private final S3ImageAccessPolicy accessPolicy;

    public R2FallbackImageUrlProvider(ImageUrlProvider primary, ImageStorage storage, BackupObjectStorage backup,
            S3StorageProperties s3, R2StorageProperties r2) {
        this.primary = Objects.requireNonNull(primary);
        this.storage = Objects.requireNonNull(storage);
        this.backup = Objects.requireNonNull(backup);
        this.s3 = Objects.requireNonNull(s3);
        this.r2 = Objects.requireNonNull(r2);
        this.accessPolicy = new S3ImageAccessPolicy(s3);
        if (!s3.environment().equals(r2.environment())) {
            throw new IllegalArgumentException("이미지 URL 발급의 S3와 R2 환경이 일치해야 합니다.");
        }
    }

    @Override
    public ImageAccessUrl createAccessUrl(String imageObjectKey) {
        String loggedKey = "invalid";
        String originalKey = "none";
        String s3Result = "NOT_CHECKED";
        String s3FailureType = "none";
        String result = "FAILED";
        String failureType = "none";
        String mime = "none";
        long size = 0;
        long startedAt = System.nanoTime();
        try {
            try {
                accessPolicy.requireGenerated(imageObjectKey);
            } catch (IllegalArgumentException exception) {
                throw new ImageStorageException("현재 환경의 생성 이미지 키가 필요합니다.", exception,
                        DiagnosticType.REQUEST_VALIDATION_ERROR);
            }
            loggedKey = imageObjectKey;
            try {
                if (storage.exists(imageObjectKey)) {
                    ImageAccessUrl url = primary.createAccessUrl(imageObjectKey);
                    s3Result = "AVAILABLE";
                    result = "S3";
                    return url;
                }
                s3Result = "MISSING";
            } catch (ImageStorageException exception) {
                // 잘못된 요청을 다른 저장소로 우회시키지 않는다.
                if (exception.diagnosticType() == DiagnosticType.REQUEST_VALIDATION_ERROR) {
                    throw exception;
                }
                s3Result = "ERROR";
                s3FailureType = exception.diagnosticType() == null ? "OTHER"
                        : exception.diagnosticType().name();
            }

            // 원본 후보 탐색을 한 번 수행한다. R2 오류를 후보 부재로 처리하거나 S3로 되돌리지 않는다.
            for (String candidate : ImageVariantKeys.originalImageKeyCandidatesForLookup(imageObjectKey)) {
                var found = backup.findMetadata(candidate);
                if (found.isEmpty()) {
                    continue;
                }
                BackupObjectMetadata metadata = found.orElseThrow();
                if (!candidate.equals(metadata.objectKey())) {
                    throw new ImageStorageException("R2 원본 응답 키가 일치하지 않습니다.", null,
                            DiagnosticType.RESPONSE_PROCESSING_ERROR);
                }
                originalKey = candidate;
                mime = metadata.mediaType().getType() + "/" + metadata.mediaType().getSubtype();
                size = metadata.size();
                ImageAccessUrl url = backup.createAccessUrl(candidate);
                result = "R2";
                return url;
            }
            throw new ImageBackupNotFoundException();
        } catch (BackupStorageException exception) {
            failureType = "R2_" + exception.failureType().name();
            throw new ImageStorageException("R2 이미지 원본을 조회하거나 URL을 발급하지 못했습니다.", exception,
                    DiagnosticType.valueOf(exception.failureType().name()));
        } catch (ImageBackupNotFoundException exception) {
            failureType = "BACKUP_NOT_FOUND";
            throw exception;
        } catch (ImageStorageException exception) {
            failureType = exception.diagnosticType() == null ? "OTHER" : exception.diagnosticType().name();
            throw exception;
        } catch (IllegalArgumentException exception) {
            failureType = "REQUEST_VALIDATION_ERROR";
            throw new ImageStorageException("R2 원본을 찾을 수 있는 이미지 키가 필요합니다.", exception,
                    DiagnosticType.REQUEST_VALIDATION_ERROR);
        } catch (RuntimeException exception) {
            failureType = "OTHER";
            throw exception;
        } finally {
            var event = "FAILED".equals(result) ? LOGGER.atWarn() : LOGGER.atInfo();
            event.addKeyValue("event", "image_url_selected").addKeyValue("environment", s3.environment())
                    .addKeyValue("s3Bucket", s3.bucket()).addKeyValue("r2Bucket", r2.bucket())
                    .addKeyValue("representativeKey", loggedKey).addKeyValue("originalKey", originalKey)
                    .addKeyValue("s3Result", s3Result).addKeyValue("result", result)
                    .addKeyValue("s3FailureType", s3FailureType)
                    .addKeyValue("failureType", failureType).addKeyValue("mime", mime).addKeyValue("size", size)
                    .addKeyValue("durationMs", (System.nanoTime() - startedAt) / 1_000_000)
                    .log("event=image_url_selected representativeKey={} originalKey={} s3Result={} result={} failureType={}",
                            loggedKey, originalKey, s3Result, result, failureType);
        }
    }
}
