package com.harudle.generation.diary.service;

import com.harudle.generation.diary.service.port.ImageBackupExecutionLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class ImageBackupScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageBackupScheduler.class);
    private final ImageBackupBatchService batches;
    private final ImageBackupExecutionLock lock;

    public ImageBackupScheduler(ImageBackupBatchService batches, ImageBackupExecutionLock lock) {
        this.batches = batches;
        this.lock = lock;
    }

    @Scheduled(cron = "${harudle.generation.storage.r2.backup-scheduler.cron:0 0 0 * * *}",
            zone = "${harudle.generation.storage.r2.backup-scheduler.zone:Asia/Seoul}", scheduler = "r2BackupTaskScheduler")
    public void backupOriginals() {
        try {
            if (!lock.executeIfAvailable(batches::backupAll)) {
                LOGGER.info("event=image_backup_schedule_skipped reason=ALREADY_RUNNING");
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("event=image_backup_schedule_failed exceptionType={}", exception.getClass().getSimpleName());
        }
    }
}
