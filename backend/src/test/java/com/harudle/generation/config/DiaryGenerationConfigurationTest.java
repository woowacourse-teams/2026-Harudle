package com.harudle.generation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import com.harudle.generation.diary.service.DiaryGenerationCompletionService;
import com.harudle.generation.diary.service.DiaryGenerationExecutor;
import com.harudle.generation.diary.service.RequestFingerprintGenerator;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class DiaryGenerationConfigurationTest {

    @Test
    @DisplayName("어댑터 빈의 등록 순서와 무관하게 생성 서비스를 연결한다")
    void wireGenerationServiceWithAdapters() {
        try (AnnotationConfigApplicationContext context = createContext(true)) {
            DiaryGenerationExecutor executor = context.getBean(DiaryGenerationExecutor.class);

            assertThat(executor.isConfigured()).isTrue();
        }
    }

    @Test
    @DisplayName("어댑터가 없으면 생성 서비스를 안전하게 비활성 상태로 둔다")
    void keepGenerationServiceUnavailableWithoutAdapters() {
        try (AnnotationConfigApplicationContext context = createContext(false)) {
            DiaryGenerationExecutor executor = context.getBean(DiaryGenerationExecutor.class);

            assertThat(executor.isConfigured()).isFalse();
        }
    }

    @Test
    @DisplayName("일부 생성 어댑터만 있으면 불완전한 구성으로 애플리케이션 시작을 중단한다")
    void rejectPartiallyConfiguredAdapters() {
        try (AnnotationConfigApplicationContext context = createBaseContext()) {
            context.registerBean(
                    "storyboardGenerator",
                    StoryboardGenerator.class,
                    () -> mock(StoryboardGenerator.class)
            );

            assertThatThrownBy(context::refresh)
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("AI 생성 어댑터는 모두 함께 구성해야 합니다.");
        }
    }

    @Test
    @DisplayName("동일한 생성 어댑터가 여러 개면 구성을 임의로 선택하지 않는다")
    void rejectAmbiguousAdapters() {
        try (AnnotationConfigApplicationContext context = createBaseContext()) {
            registerAdapters(context);
            context.registerBean(
                    "secondStoryboardGenerator",
                    StoryboardGenerator.class,
                    () -> mock(StoryboardGenerator.class)
            );

            assertThatThrownBy(context::refresh)
                    .hasRootCauseInstanceOf(NoUniqueBeanDefinitionException.class);
        }
    }

    private AnnotationConfigApplicationContext createContext(boolean registerAdapters) {
        AnnotationConfigApplicationContext context = createBaseContext();
        if (registerAdapters) {
            registerAdapters(context);
        }
        context.refresh();
        return context;
    }

    private AnnotationConfigApplicationContext createBaseContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of(
                "harudle.generation.lifecycle.processing-timeout=15m",
                "harudle.generation.lifecycle.cleanup-interval=1m"
        ).applyTo(context);
        context.registerBean(
                RequestFingerprintGenerator.class,
                () -> mock(RequestFingerprintGenerator.class)
        );
        context.registerBean(GenerationPromptRepository.class, () -> mock(GenerationPromptRepository.class));
        context.registerBean(DiaryGenerationRepository.class, () -> mock(DiaryGenerationRepository.class));
        context.registerBean(
                DiaryGenerationCompletionService.class,
                () -> mock(DiaryGenerationCompletionService.class)
        );
        context.register(DiaryGenerationConfiguration.class);
        return context;
    }

    private void registerAdapters(AnnotationConfigApplicationContext context) {
        context.registerBean(
                "storyboardGenerator",
                StoryboardGenerator.class,
                () -> mock(StoryboardGenerator.class)
        );
        context.registerBean(
                "diaryImageGenerator",
                DiaryImageGenerator.class,
                () -> mock(DiaryImageGenerator.class)
        );
        context.registerBean("imageStorage", ImageStorage.class, () -> mock(ImageStorage.class));
    }
}
