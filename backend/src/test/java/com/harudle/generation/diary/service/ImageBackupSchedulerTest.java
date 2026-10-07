package com.harudle.generation.diary.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.harudle.generation.diary.service.port.ImageBackupExecutionLock;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

@ExtendWith(OutputCaptureExtension.class)
class ImageBackupSchedulerTest {
    private final ImageBackupBatchService batches = mock(ImageBackupBatchService.class);
    private final ImageBackupExecutionLock lock = mock(ImageBackupExecutionLock.class);
    private final ImageBackupScheduler scheduler = new ImageBackupScheduler(batches, lock);

    @Test
    void availableLockRunsBatch() {
        when(lock.executeIfAvailable(any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return true;
        });
        scheduler.backupOriginals();
        verify(batches).backupAll();
    }

    @Test
    void occupiedLockSkipsAllTargetAndStorageWork(CapturedOutput output) {
        scheduler.backupOriginals();
        verifyNoInteractions(batches);
        assertThat(output).contains("reason=ALREADY_RUNNING");
    }

    @Test
    void failedExecutionDoesNotDisableNextScheduleOrExposeSecrets(CapturedOutput output) {
        when(lock.executeIfAvailable(any())).thenThrow(new IllegalStateException("fake-secret"))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(0)).run();
                    return true;
                });
        scheduler.backupOriginals();
        scheduler.backupOriginals();
        verify(batches).backupAll();
        assertThat(output).contains("image_backup_schedule_failed").doesNotContain("fake-secret");
    }

    @Test
    void defaultCronRunsEveryTwentyFourHoursAtKoreanMidnightOnDedicatedScheduler() throws Exception {
        Scheduled annotation = ImageBackupScheduler.class.getMethod("backupOriginals").getAnnotation(Scheduled.class);
        assertThat(annotation.scheduler()).isEqualTo("r2BackupTaskScheduler");
        String cron = annotation.cron().substring(annotation.cron().indexOf(':') + 1, annotation.cron().length() - 1);
        String zone = annotation.zone().substring(annotation.zone().indexOf(':') + 1, annotation.zone().length() - 1);
        var expression = CronExpression.parse(cron);
        var first = expression.next(ZonedDateTime.of(2026, 10, 7, 9, 0, 0, 0, ZoneId.of(zone)));
        assertThat(first).isEqualTo(ZonedDateTime.of(2026, 10, 8, 0, 0, 0, 0, ZoneId.of("Asia/Seoul")));
        assertThat(Duration.between(first, expression.next(first))).isEqualTo(Duration.ofHours(24));
    }
}
