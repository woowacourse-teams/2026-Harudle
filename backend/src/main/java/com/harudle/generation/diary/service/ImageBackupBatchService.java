package com.harudle.generation.diary.service;

import com.harudle.generation.config.ImageBackupScheduleProperties;
import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.repository.ImageBackupTarget;
import com.harudle.generation.diary.service.dto.ImageBackupBatchResult;
import com.harudle.generation.diary.service.dto.ImageBackupBatchResult.Status;
import com.harudle.generation.diary.service.exception.ImageBackupException;
import com.harudle.generation.diary.service.port.BackupStorageException;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.BackupUploadResult;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;

/** 저장소 통신 동안 JPA 트랜잭션을 보유하지 않고 완료 시각·ID 커서로 순회한다. */
public final class ImageBackupBatchService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageBackupBatchService.class);
    private final DiaryGenerationRepository generations;
    private final ImageBackupService backups;
    private final ImageBackupScheduleProperties schedule;
    private final S3StorageProperties source;
    private final Clock clock;

    public ImageBackupBatchService(DiaryGenerationRepository generations, ImageBackupService backups,
            ImageBackupScheduleProperties schedule, S3StorageProperties source, Clock clock) {
        this.generations = generations;
        this.backups = backups;
        this.schedule = schedule;
        this.source = source;
        this.clock = clock;
    }

    public ImageBackupBatchResult backupAll() {
        UUID runId = UUID.randomUUID();
        Instant cutoff = clock.instant();
        long startedAt = System.nanoTime();
        long processed = 0, uploaded = 0, existing = 0, missing = 0, failed = 0;
        Status status = Status.FAILED;
        String failureType = "none";
        try {
            ImageBackupTarget cursor = null;
            while (!Thread.currentThread().isInterrupted()) {
                List<ImageBackupTarget> page = nextPage(cutoff, cursor);
                if (page.isEmpty()) {
                    break;
                }
                for (ImageBackupTarget target : page) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    processed++;
                    try {
                        var result = backups.backup(target.imageObjectKey());
                        if (result.isEmpty()) {
                            missing++;
                        } else if (result.orElseThrow().uploadResult() == BackupUploadResult.UPLOADED) {
                            uploaded++;
                        } else {
                            existing++;
                        }
                    } catch (RuntimeException exception) {
                        failed++;
                        LOGGER.atWarn().addKeyValue("event", "image_backup_batch_target_failed")
                                .addKeyValue("runId", runId).addKeyValue("generationId", target.generationId())
                                .addKeyValue("failureType", failureCode(exception))
                                .log("event=image_backup_batch_target_failed runId={} generationId={} failureType={}",
                                        runId, target.generationId(), failureCode(exception));
                    }
                }
                cursor = page.getLast();
                if (page.size() < schedule.batchSize()) {
                    break;
                }
            }
            status = Thread.currentThread().isInterrupted() ? Status.INTERRUPTED
                    : (missing + failed > 0 ? Status.PARTIAL_FAILURE : Status.COMPLETED);
            return new ImageBackupBatchResult(runId, cutoff, status, processed, uploaded, existing, missing, failed);
        } catch (RuntimeException exception) {
            failureType = exception.getClass().getSimpleName();
            throw exception;
        } finally {
            var event = status == Status.COMPLETED ? LOGGER.atInfo() : LOGGER.atWarn();
            event.addKeyValue("event", "image_backup_batch_completed").addKeyValue("runId", runId)
                    .addKeyValue("environment", source.environment()).addKeyValue("s3Bucket", source.bucket())
                    .addKeyValue("cutoff", cutoff).addKeyValue("status", status).addKeyValue("failureType", failureType)
                    .addKeyValue("processedCount", processed).addKeyValue("uploadedCount", uploaded)
                    .addKeyValue("existingVerifiedCount", existing).addKeyValue("originalMissingCount", missing)
                    .addKeyValue("failedCount", failed).addKeyValue("completedAt", clock.instant())
                    .addKeyValue("durationMs", (System.nanoTime() - startedAt) / 1_000_000)
                    .log("event=image_backup_batch_completed runId={} status={} processedCount={} "
                                    + "uploadedCount={} existingVerifiedCount={} originalMissingCount={} failedCount={}",
                            runId, status, processed, uploaded, existing, missing, failed);
        }
    }

    private List<ImageBackupTarget> nextPage(Instant cutoff, ImageBackupTarget cursor) {
        String keyPattern = source.generatedPrefix() + "/%";
        var page = PageRequest.of(0, schedule.batchSize());
        return cursor == null
                ? generations.findImageBackupTargets(GenerationStatus.SUCCEEDED, cutoff, keyPattern, page)
                : generations.findImageBackupTargetsAfter(GenerationStatus.SUCCEEDED, cutoff, keyPattern,
                        cursor.completedAt(), cursor.generationId(), page);
    }

    private String failureCode(RuntimeException exception) {
        if (exception instanceof ImageBackupException backup) {
            return backup.reason().name();
        }
        if (exception instanceof BackupStorageException storage) {
            return "R2_" + storage.failureType().name();
        }
        if (exception instanceof ImageStorageException storage) {
            return "S3_" + (storage.diagnosticType() == null ? "OTHER" : storage.diagnosticType().name());
        }
        return exception instanceof IllegalArgumentException ? "INVALID_IMAGE_KEY" : "OTHER";
    }
}
