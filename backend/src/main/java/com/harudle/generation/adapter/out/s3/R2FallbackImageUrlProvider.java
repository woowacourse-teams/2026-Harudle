package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.config.R2StorageProperties;
import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.domain.ImageVariantKeys;
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

/** S3를 우선 확인하고 R2 원본을 조회하되, 백업 부재·조회 오류에서는 기존 S3 URL 발급을 유지한다. */
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
        String r2Result = "NOT_CHECKED";
        String r2FailureType = "none";
        String result = "FAILED";
        String failureType = "none";
        String mime = "none";
        long size = 0;
        ImageStorageException signingFailure = null;
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
                    s3Result = "AVAILABLE";
                    try {
                        ImageAccessUrl url = primary.createAccessUrl(imageObjectKey);
                        result = "S3";
                        return url;
                    } catch (ImageStorageException exception) {
                        signingFailure = exception;
                        throw exception;
                    }
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

            // R2 후보 탐색은 한 번만 수행하고, 조회 오류와 백업 부재를 별도로 기록한다.
            try {
                for (String candidate : ImageVariantKeys.originalImageKeyCandidatesForLookup(imageObjectKey)) {
                    var found = backup.findMetadata(candidate);
                    if (found.isEmpty()) {
                        continue;
                    }
                    BackupObjectMetadata metadata = found.orElseThrow();
                    if (!candidate.equals(metadata.objectKey())) {
                        throw new BackupStorageException(BackupStorageException.FailureType.RESPONSE_PROCESSING_ERROR,
                                new IllegalArgumentException("R2 원본 응답 키가 일치하지 않습니다."));
                    }
                    originalKey = candidate;
                    mime = metadata.mediaType().getType() + "/" + metadata.mediaType().getSubtype();
                    size = metadata.size();
                    ImageAccessUrl url = backup.createAccessUrl(candidate);
                    r2Result = "AVAILABLE";
                    result = "R2";
                    return url;
                }
                r2Result = "MISSING";
            } catch (BackupStorageException exception) {
                r2Result = "ERROR";
                r2FailureType = exception.failureType().name();
                if (exception.failureType() == BackupStorageException.FailureType.REQUEST_VALIDATION_ERROR) {
                    throw new ImageStorageException("현재 환경의 유효한 R2 원본 키가 필요합니다.", exception,
                            DiagnosticType.REQUEST_VALIDATION_ERROR);
                }
            }

            // 이미 실패한 서명 발급은 반복하지 않는다. HEAD 실패와 서명 발급 실패는 별개다.
            if (signingFailure != null) {
                throw signingFailure;
            }
            ImageAccessUrl url = primary.createAccessUrl(imageObjectKey);
            result = "S3_FALLBACK";
            return url;
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
            var event = "FAILED".equals(result) || "S3_FALLBACK".equals(result) ? LOGGER.atWarn() : LOGGER.atInfo();
            event.addKeyValue("event", "image_url_selected").addKeyValue("environment", s3.environment())
                    .addKeyValue("s3Bucket", s3.bucket()).addKeyValue("r2Bucket", r2.bucket())
                    .addKeyValue("representativeKey", loggedKey).addKeyValue("originalKey", originalKey)
                    .addKeyValue("s3Result", s3Result).addKeyValue("result", result)
                    .addKeyValue("s3FailureType", s3FailureType)
                    .addKeyValue("r2Result", r2Result).addKeyValue("r2FailureType", r2FailureType)
                    .addKeyValue("failureType", failureType).addKeyValue("mime", mime).addKeyValue("size", size)
                    .addKeyValue("durationMs", (System.nanoTime() - startedAt) / 1_000_000)
                    .log("event=image_url_selected representativeKey={} originalKey={} s3Result={} r2Result={} "
                                    + "r2FailureType={} result={} failureType={}",
                            loggedKey, originalKey, s3Result, r2Result, r2FailureType, result, failureType);
        }
    }
}
