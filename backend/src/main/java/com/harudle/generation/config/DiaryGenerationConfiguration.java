package com.harudle.generation.config;

import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.ClaimedDiaryGenerationService;
import com.harudle.generation.diary.service.DiaryGenerationCompletionService;
import com.harudle.generation.diary.service.DiaryGenerationExecutor;
import com.harudle.generation.diary.service.DiaryGenerationWorker;
import com.harudle.generation.diary.service.GenerationLifecycleMetrics;
import com.harudle.generation.diary.service.ObservedDiaryGenerationExecutor;
import com.harudle.generation.diary.service.PendingDiaryGenerationClaimService;
import com.harudle.generation.diary.service.RequestFingerprintGenerator;
import com.harudle.generation.diary.service.dto.CompletedDiaryGeneration;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import com.harudle.generation.diary.service.exception.GenerationUnavailableException;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.RequestFingerprintProtector;
import com.harudle.generation.diary.service.storyboard.StoryboardGenerationService;
import com.harudle.generation.prompt.infrastructure.GenerationPromptBootstrapService;
import com.harudle.generation.prompt.infrastructure.GenerationPromptInitializer;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import com.harudle.generation.loadtest.LoadTestGenerationRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ExecutorConfigurationSupport;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({
        GenerationPromptBootstrapProperties.class,
        GenerationLifecycleProperties.class,
        GenerationWorkerProperties.class
})
class DiaryGenerationConfiguration {

    private static final Duration WORKER_SHUTDOWN_TIMEOUT = Duration.ofMinutes(6);
    // HTTP 요청 처리가 멈춘 뒤, 다른 실행기와 DB 연결이 종료되기 전에 생성 작업을 마칠 시간을 준다.
    private static final int WORKER_SHUTDOWN_PHASE = ExecutorConfigurationSupport.DEFAULT_PHASE + 1;

    @Bean
    @ConditionalOnMissingBean(name = "taskScheduler")
    ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("generation-scheduler-");
        return scheduler;
    }

    @Bean
    ThreadPoolTaskExecutor generationWorkerTaskExecutor(GenerationWorkerProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.concurrency());
        executor.setMaxPoolSize(properties.concurrency());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("generation-worker-");
        executor.setStrictEarlyShutdown(true);
        executor.setPhase(WORKER_SHUTDOWN_PHASE);
        return executor;
    }

    @Bean(destroyMethod = "requestStop")
    DiaryGenerationWorker diaryGenerationWorker(
            PendingDiaryGenerationClaimService claimService,
            DiaryGenerationRepository generationRepository,
            DiaryRepository diaryRepository,
            DiarySourceTextCipher sourceTextCipher,
            DiaryGenerationExecutor generationExecutor,
            ObjectProvider<LoadTestGenerationRegistry> loadTestRegistryProvider,
            DiaryGenerationCompletionService completionService,
            @Qualifier("generationWorkerTaskExecutor") ThreadPoolTaskExecutor taskExecutor,
            GenerationWorkerProperties properties
    ) {
        return new DiaryGenerationWorker(claimService, generationRepository, diaryRepository, sourceTextCipher,
                generationExecutor,
                Optional.ofNullable(loadTestRegistryProvider.getIfAvailable()),
                completionService, taskExecutor, properties);
    }

    @Bean
    ApplicationListener<ContextClosedEvent> generationWorkerShutdownListener(
            ApplicationContext context,
            DiaryGenerationWorker worker,
            @Qualifier("generationWorkerTaskExecutor") ThreadPoolTaskExecutor executor
    ) {
        return event -> {
            if (event.getApplicationContext() == context) {
                worker.requestStop();
                DefaultLifecycleProcessor lifecycleProcessor = context.getBean(
                        AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME,
                        DefaultLifecycleProcessor.class
                );
                lifecycleProcessor.setTimeoutForShutdownPhase(executor.getPhase(), WORKER_SHUTDOWN_TIMEOUT.toMillis());
            }
        };
    }

    @Bean
    @Primary
    DiaryGenerationExecutor diaryGenerationExecutor(
            RequestFingerprintGenerator requestFingerprintGenerator,
            RequestFingerprintProtector requestFingerprintProtector,
            GenerationPromptRepository generationPromptRepository,
            DiaryGenerationRepository diaryGenerationRepository,
            ObjectProvider<StoryboardGenerationService> storyboardServiceProvider,
            ObjectProvider<DiaryImageGenerator> diaryImageGeneratorProvider,
            ObjectProvider<ImageStorage> imageStorageProvider,
            DiaryGenerationCompletionService completionService,
            GenerationLifecycleMetrics lifecycleMetrics,
            MeterRegistry meterRegistry
    ) {
        Optional<StoryboardGenerationService> storyboardService = findAdapter(storyboardServiceProvider);
        Optional<DiaryImageGenerator> diaryImageGenerator = findAdapter(diaryImageGeneratorProvider);
        Optional<ImageStorage> imageStorage = findAdapter(imageStorageProvider);
        if (storyboardService.isEmpty()
                && diaryImageGenerator.isEmpty()
                && imageStorage.isEmpty()) {
            return new UnavailableDiaryGenerationExecutor();
        }
        if (storyboardService.isEmpty()
                || diaryImageGenerator.isEmpty()
                || imageStorage.isEmpty()) {
            throw new IllegalStateException("AI 생성 어댑터는 모두 함께 구성해야 합니다.");
        }

        DiaryGenerationExecutor executor = new ClaimedDiaryGenerationService(
                requestFingerprintGenerator,
                requestFingerprintProtector,
                generationPromptRepository,
                diaryGenerationRepository,
                storyboardService.orElseThrow(),
                diaryImageGenerator.orElseThrow(),
                imageStorage.orElseThrow(),
                completionService,
                lifecycleMetrics
        );
        return new ObservedDiaryGenerationExecutor(executor, meterRegistry);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "harudle.generation.prompt-bootstrap",
            name = "enabled",
            havingValue = "true"
    )
    GenerationPromptInitializer generationPromptInitializer(
            GenerationPromptBootstrapProperties properties,
            GenerationPromptRepository generationPromptRepository,
            ImageStorage imageStorage,
            GenerationPromptBootstrapService bootstrapService
    ) {
        return new GenerationPromptInitializer(
                properties,
                generationPromptRepository,
                imageStorage,
                bootstrapService
        );
    }

    private static <T> Optional<T> findAdapter(ObjectProvider<T> provider) {
        return Optional.ofNullable(provider.getIfAvailable());
    }

    private static final class UnavailableDiaryGenerationExecutor implements DiaryGenerationExecutor {

        @Override
        public boolean isConfigured() {
            return false;
        }

        @Override
        public CompletedDiaryGeneration generate(GenerateDiaryImageCommand command, UUID generationId) {
            throw GenerationUnavailableException.adaptersNotConfigured();
        }
    }
}
