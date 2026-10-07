package com.harudle.generation.config;

import com.harudle.generation.diary.service.ImageBackupService;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.ImageStorage;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {
        "harudle.generation.adapters.enabled",
        "harudle.generation.storage.r2.enabled"
}, havingValue = "true")
public class ImageBackupConfiguration {

    @Bean
    public ImageBackupService imageBackupService(
            @Qualifier("imageStorage") ImageStorage sourceStorage,
            BackupObjectStorage backupStorage,
            S3StorageProperties sourceProperties,
            R2StorageProperties backupProperties,
            @Qualifier("serviceClock") Clock clock
    ) {
        return new ImageBackupService(sourceStorage, backupStorage, sourceProperties, backupProperties, clock);
    }
}
