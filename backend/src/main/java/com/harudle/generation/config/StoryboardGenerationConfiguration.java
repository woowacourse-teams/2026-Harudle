package com.harudle.generation.config;

import com.harudle.generation.diary.service.port.StoryboardGenerator;
import com.harudle.generation.diary.service.storyboard.StoryboardAttemptRunner;
import com.harudle.generation.diary.service.storyboard.StoryboardGenerationService;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "harudle.generation.adapters", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(StoryboardProviderProperties.class)
public class StoryboardGenerationConfiguration {

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService storyboardAttemptExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("storyboard-attempt-", 0).factory());
    }

    @Bean
    public StoryboardAttemptRunner storyboardAttemptRunner(
            @Qualifier("storyboardAttemptExecutor") ExecutorService executor
    ) {
        return new StoryboardAttemptRunner(executor);
    }

    @Bean
    public StoryboardGenerationService storyboardGenerationService(
            Map<String, StoryboardGenerator> generators,
            StoryboardAttemptRunner runner,
            StoryboardProviderProperties properties
    ) {
        StoryboardGenerator primary = requiredGenerator(generators, properties.provider());
        Optional<StoryboardGenerator> fallback = properties.fallbackEnabled()
                ? Optional.of(requiredGenerator(generators, properties.fallbackProvider())) : Optional.empty();
        return new StoryboardGenerationService(primary, fallback, runner, properties);
    }

    private static StoryboardGenerator requiredGenerator(Map<String, StoryboardGenerator> generators, String provider) {
        StoryboardGenerator generator = generators.get(provider + "StoryboardGenerator");
        if (generator == null) {
            throw new IllegalStateException("선택한 스토리보드 Provider 구현체가 없습니다: " + provider);
        }
        return generator;
    }
}
