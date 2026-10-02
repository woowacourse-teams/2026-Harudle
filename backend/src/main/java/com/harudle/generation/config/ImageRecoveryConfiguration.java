package com.harudle.generation.config;

import com.harudle.generation.diary.service.ImageRecoveryService;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.ImageStorage;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"harudle.generation.adapters.enabled", "harudle.generation.storage.r2.enabled"},
        havingValue = "true")
public class ImageRecoveryConfiguration {
    @Bean
    public ImageRecoveryService imageRecoveryService(@Qualifier("imageStorage") ImageStorage storage,
            BackupObjectStorage backup, S3StorageProperties s3, R2StorageProperties r2,
            @Qualifier("serviceClock") Clock clock) {
        return new ImageRecoveryService(storage, backup, s3, r2, clock);
    }
}
