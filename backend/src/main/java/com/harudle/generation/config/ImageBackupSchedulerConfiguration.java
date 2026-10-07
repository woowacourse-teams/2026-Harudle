package com.harudle.generation.config;

import com.harudle.generation.adapter.out.db.PostgresImageBackupExecutionLock;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.ImageBackupBatchService;
import com.harudle.generation.diary.service.ImageBackupScheduler;
import com.harudle.generation.diary.service.ImageBackupService;
import com.harudle.generation.diary.service.port.ImageBackupExecutionLock;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "harudle.generation.storage.r2.backup-scheduler", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ImageBackupScheduleProperties.class)
public class ImageBackupSchedulerConfiguration {
    @Bean
    ImageBackupBatchService imageBackupBatchService(DiaryGenerationRepository generations, ImageBackupService backups,
            ImageBackupScheduleProperties schedule, S3StorageProperties s3, R2StorageProperties r2,
            @Qualifier("serviceClock") Clock clock) {
        if (!s3.isEnvironmentMatched() || !"prod".equals(s3.environment())
                || !"prod".equals(r2.environment()) || !r2.enabled()) {
            throw new IllegalStateException("자동 R2 백업은 S3와 R2가 활성화된 prod 환경에서만 실행할 수 있습니다.");
        }
        return new ImageBackupBatchService(generations, backups, schedule, s3, clock);
    }

    @Bean
    ImageBackupExecutionLock imageBackupExecutionLock(DataSource dataSource) {
        return new PostgresImageBackupExecutionLock(dataSource);
    }

    @Bean
    ImageBackupScheduler imageBackupScheduler(ImageBackupBatchService batches, ImageBackupExecutionLock lock) {
        return new ImageBackupScheduler(batches, lock);
    }

    @Bean
    ThreadPoolTaskScheduler r2BackupTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("r2-backup-");
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
