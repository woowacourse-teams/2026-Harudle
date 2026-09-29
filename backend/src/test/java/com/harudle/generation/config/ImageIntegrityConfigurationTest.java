package com.harudle.generation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.harudle.generation.diary.repository.ImageIntegrityCandidateRepository;
import com.harudle.generation.diary.service.ImageIntegrityMonitor;
import com.harudle.generation.diary.service.port.ImageStorage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class ImageIntegrityConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ImageIntegrityConfiguration.class)
            .withBean(ImageIntegrityCandidateRepository.class,
                    () -> mock(ImageIntegrityCandidateRepository.class))
            .withBean(ImageStorage.class, () -> mock(ImageStorage.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean("serviceClock", Clock.class, Clock::systemUTC);

    @Test
    void staysDisabledUntilEnvironmentIsReady() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ImageIntegrityMonitor.class);
            assertThat(context).doesNotHaveBean(ThreadPoolTaskScheduler.class);
        });
    }

    @Test
    void usesDedicatedSchedulerWhenEnabled() {
        contextRunner.withPropertyValues(
                "harudle.image-integrity.enabled=true",
                "harudle.image-integrity.interval=1m",
                "harudle.image-integrity.initial-delay=1m",
                "harudle.image-integrity.page-size=100",
                "harudle.image-integrity.max-checks-per-run=500",
                "harudle.image-integrity.minimum-age=5m",
                "harudle.image-integrity.max-run-duration=1m"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ImageIntegrityMonitor.class);
            assertThat(context).hasSingleBean(ThreadPoolTaskScheduler.class);
        });
    }
}
