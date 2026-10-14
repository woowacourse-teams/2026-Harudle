package com.harudle.diary.service;

import com.harudle.generation.config.StoryboardProviderProperties;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.ClaimedDiaryGenerationService;
import com.harudle.generation.diary.service.DiaryGenerationCompletionService;
import com.harudle.generation.diary.service.GenerationLifecycleMetrics;
import com.harudle.generation.diary.service.ObservedDiaryGenerationExecutor;
import com.harudle.generation.diary.service.RequestFingerprintGenerator;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.storyboard.StoryboardAttemptRunner;
import com.harudle.generation.diary.service.storyboard.StoryboardGenerationService;
import com.harudle.generation.loadtest.LoadTestAiStub;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "DEPLOY_ENV", havingValue = "dev")
class LoadTestDiaryConfiguration {

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService loadTestStoryboardExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("load-test-storyboard-", 0).factory());
    }

    @Bean
    LoadTestDiaryCreationService loadTestDiaryCreationService(
            MemberDiaryCreationTransactionService memberTransactionService,
            RequestFingerprintGenerator fingerprintGenerator,
            GenerationPromptRepository prompts,
            DiaryGenerationRepository generations,
            ImageStorage imageStorage,
            DiaryGenerationCompletionService completionService,
            GenerationLifecycleMetrics lifecycleMetrics,
            MeterRegistry meterRegistry,
            @Qualifier("loadTestStoryboardExecutor") ExecutorService executor
    ) {
        var stub = new LoadTestAiStub();
        var properties = new StoryboardProviderProperties("gemini", "none", Duration.ofSeconds(60), Duration.ofSeconds(60));
        var storyboardService = new StoryboardGenerationService(request -> stub.storyboard(), Optional.empty(),
                new StoryboardAttemptRunner(executor), properties);
        var generationService = new ClaimedDiaryGenerationService(
                fingerprintGenerator, prompts, generations, storyboardService,
                request -> stub.generateImage(request.referenceImage()), imageStorage, completionService, lifecycleMetrics
        );
        var executionService = new DiaryCreationExecutionService(
                new ObservedDiaryGenerationExecutor(generationService, meterRegistry));
        return new LoadTestDiaryCreationService(new DiaryCreationService(memberTransactionService, executionService));
    }
}
