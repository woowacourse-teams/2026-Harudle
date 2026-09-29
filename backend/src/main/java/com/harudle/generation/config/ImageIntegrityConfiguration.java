package com.harudle.generation.config;

import com.harudle.generation.diary.repository.ImageIntegrityCandidateRepository;
import com.harudle.generation.diary.service.ImageIntegrityMonitor;
import com.harudle.generation.diary.service.port.ImageStorage;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "harudle.image-integrity", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ImageIntegrityProperties.class)
class ImageIntegrityConfiguration {

    @Bean
    ThreadPoolTaskScheduler imageIntegrityScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("image-integrity-");
        return scheduler;
    }

    @Bean
    ImageIntegrityMonitor imageIntegrityMonitor(
            ImageIntegrityCandidateRepository candidates,
            ImageStorage imageStorage,
            MeterRegistry meterRegistry,
            @Qualifier("serviceClock") Clock clock,
            ImageIntegrityProperties properties
    ) {
        return new ImageIntegrityMonitor(candidates, imageStorage, meterRegistry, clock, properties);
    }
}
