package com.harudle.generation.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.ImageBackupBatchService;
import com.harudle.generation.diary.service.ImageBackupScheduler;
import com.harudle.generation.diary.service.ImageBackupService;
import java.net.URI;
import java.time.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.unit.DataSize;

class ImageBackupSchedulerConfigurationTest {
    private static final String PREFIX = "harudle.generation.storage.r2.backup-scheduler.";
    private final ImageBackupService backups = mock(ImageBackupService.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ImageBackupSchedulerConfiguration.class);

    @Test
    void disabledSchedulerDoesNotRequireStoragesOrBindUnusedProperties() {
        runner.withPropertyValues(PREFIX + "cron=invalid", PREFIX + "zone=invalid", PREFIX + "batch-size=invalid")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ImageBackupScheduleProperties.class);
                    assertThat(context).doesNotHaveBean(ImageBackupScheduler.class);
                    assertThat(context).doesNotHaveBean("r2BackupTaskScheduler");
                });
    }

    @Test
    void enabledProdSchedulerDefaultsToDailyMidnightWithoutRunningBackupOnStartup() {
        enabled("prod", "prod").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ImageBackupScheduler.class);
            var settings = context.getBean(ImageBackupScheduleProperties.class);
            assertThat(settings.cron()).isEqualTo("0 0 0 * * *");
            assertThat(settings.zone()).isEqualTo("Asia/Seoul");
            assertThat(settings.batchSize()).isEqualTo(100);
            assertThat(context.getBean("r2BackupTaskScheduler", ThreadPoolTaskScheduler.class)
                    .getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            verifyNoInteractions(backups, dataSource);
        });
    }

    @ParameterizedTest
    @CsvSource({"dev,dev", "dev,prod", "prod,dev"})
    void rejectsNonProductionOrMixedStorageEnvironments(String s3, String r2) {
        enabled(s3, r2).run(context -> {
            assertThat(context).hasFailed();
            verifyNoInteractions(backups, dataSource);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"cron=invalid", "cron=-", "zone=Invalid/Zone", "batch-size=0", "batch-size=-1", "batch-size=1001"})
    void invalidSettingsFailBeforeAnyStorageOperation(String property) {
        enabled("prod", "prod").withPropertyValues(PREFIX + property).run(context -> {
            assertThat(context).hasFailed();
            verifyNoInteractions(backups, dataSource);
        });
    }

    @Test
    void missingBackupServiceRejectsEnabledScheduler() {
        runner.withPropertyValues(PREFIX + "enabled=true").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void configuredCronZoneAndPageSizeAreBound() {
        enabled("prod", "prod").withPropertyValues(PREFIX + "cron=0 0 3 * * *", PREFIX + "zone=UTC",
                PREFIX + "batch-size=10").run(context -> {
            assertThat(context).hasNotFailed();
            var settings = context.getBean(ImageBackupScheduleProperties.class);
            assertThat(settings.cron()).isEqualTo("0 0 3 * * *");
            assertThat(settings.zone()).isEqualTo("UTC");
            assertThat(settings.batchSize()).isEqualTo(10);
        });
    }

    @Test
    void blockedBackupThreadDoesNotBlockDefaultScheduler() {
        enabled("prod", "prod").withBean("taskScheduler", ThreadPoolTaskScheduler.class, ThreadPoolTaskScheduler::new)
                .run(context -> {
                    var backupScheduler = context.getBean("r2BackupTaskScheduler", ThreadPoolTaskScheduler.class);
                    var defaultScheduler = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
                    var started = new CountDownLatch(1);
                    var release = new CountDownLatch(1);
                    var cleaned = new CountDownLatch(1);
                    try {
                        backupScheduler.schedule(() -> {
                            started.countDown();
                            try { release.await(); } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        }, Instant.now());
                        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                        defaultScheduler.schedule(cleaned::countDown, Instant.now());
                        assertThat(cleaned.await(5, TimeUnit.SECONDS)).isTrue();
                    } finally {
                        release.countDown();
                    }
                });
    }

    private ApplicationContextRunner enabled(String s3Environment, String r2Environment) {
        return runner.withPropertyValues(PREFIX + "enabled=true")
                .withBean(ImageBackupService.class, () -> backups)
                .withBean(DiaryGenerationRepository.class, () -> mock(DiaryGenerationRepository.class))
                .withBean(DataSource.class, () -> dataSource)
                .withBean("serviceClock", Clock.class, Clock::systemUTC)
                .withBean(S3StorageProperties.class, () -> new S3StorageProperties("source", "ap-northeast-2", s3Environment,
                        "harudle/generated/diary-images/" + s3Environment, "harudle/references/generation/" + s3Environment,
                        DataSize.ofMegabytes(20), Duration.ofMinutes(15)))
                .withBean(R2StorageProperties.class, () -> new R2StorageProperties(true, r2Environment,
                        URI.create("https://example.r2.cloudflarestorage.com"), "backup", "fake-key", "fake-secret",
                        Duration.ofMinutes(15), DataSize.ofMegabytes(20), Duration.ofSeconds(2), Duration.ofSeconds(2)));
    }
}
