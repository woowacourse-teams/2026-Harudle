package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.DiaryGenerationCompletionService;
import com.harudle.generation.diary.service.DiaryGenerationExecutor;
import com.harudle.generation.diary.service.GenerationLifecycleMetrics;
import com.harudle.generation.diary.service.RequestFingerprintGenerator;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.RequestFingerprintProtector;
import com.harudle.generation.diary.service.storyboard.StoryboardGenerationService;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LoadTestDiaryConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(LoadTestDiaryConfiguration.class);

    @Test
    void isAbsentInProductionOrWithoutEnvironment() {
        for (String environment : new String[]{"prod", ""}) {
            context.withPropertyValues("DEPLOY_ENV=" + environment, "harudle.generation.adapters.enabled=true")
                    .run(application -> assertThat(application).hasNotFailed()
                            .doesNotHaveBean(LoadTestDiaryCreationService.class));
        }
    }

    @Test
    void devStartsWithoutStorageWhenAdaptersAreDisabledOrUnset() {
        for (String enabled : new String[]{"false", ""}) {
            context.withPropertyValues("DEPLOY_ENV=dev", "harudle.generation.adapters.enabled=" + enabled)
                    .run(application -> assertThat(application).hasNotFailed()
                            .doesNotHaveBean(LoadTestDiaryCreationService.class)
                            .doesNotHaveBean("loadTestStoryboardExecutor"));
        }
    }

    @Test
    void devRegistersSeparateServiceWithoutExtraProfileOrReplacingExistingAi() {
        var normalCreation = mock(DiaryCreationService.class);
        var normalExecutor = mock(DiaryGenerationExecutor.class);
        var normalStoryboard = mock(StoryboardGenerationService.class);
        var normalImageGenerator = mock(DiaryImageGenerator.class);
        context.withPropertyValues("spring.profiles.active=prod", "DEPLOY_ENV=dev", "harudle.generation.adapters.enabled=true")
                .withBean(MemberDiaryCreationTransactionService.class, () -> mock(MemberDiaryCreationTransactionService.class))
                .withBean(RequestFingerprintGenerator.class, () -> mock(RequestFingerprintGenerator.class))
                .withBean(RequestFingerprintProtector.class, () -> mock(RequestFingerprintProtector.class))
                .withBean(GenerationPromptRepository.class, () -> mock(GenerationPromptRepository.class))
                .withBean(DiaryGenerationRepository.class, () -> mock(DiaryGenerationRepository.class))
                .withBean(ImageStorage.class, () -> mock(ImageStorage.class))
                .withBean(DiaryGenerationCompletionService.class, () -> mock(DiaryGenerationCompletionService.class))
                .withBean(GenerationLifecycleMetrics.class, () -> mock(GenerationLifecycleMetrics.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(DiaryCreationService.class, () -> normalCreation)
                .withBean(DiaryGenerationExecutor.class, () -> normalExecutor)
                .withBean(StoryboardGenerationService.class, () -> normalStoryboard)
                .withBean(DiaryImageGenerator.class, () -> normalImageGenerator)
                .run(application -> {
                    assertThat(application).hasNotFailed().hasSingleBean(LoadTestDiaryCreationService.class);
                    assertThat(application.getBean(DiaryCreationService.class)).isSameAs(normalCreation);
                    assertThat(application.getBean(DiaryGenerationExecutor.class)).isSameAs(normalExecutor);
                    assertThat(application.getBean(StoryboardGenerationService.class)).isSameAs(normalStoryboard);
                    assertThat(application.getBean(DiaryImageGenerator.class)).isSameAs(normalImageGenerator);
                });
    }
}
