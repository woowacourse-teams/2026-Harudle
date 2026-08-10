package com.harudle.generation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.harudle.generation.repository.ComicGenerationRepository;
import com.harudle.generation.repository.GenerationPromptRepository;
import com.harudle.generation.service.ClaimedComicGenerationService;
import com.harudle.generation.service.ComicGenerationCompletionService;
import com.harudle.generation.service.RequestFingerprintGenerator;
import com.harudle.generation.service.port.ComicImageGenerator;
import com.harudle.generation.service.port.ImageStorage;
import com.harudle.generation.service.port.StoryboardGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ComicGenerationConfigurationTest {

    @Test
    @DisplayName("어댑터 빈의 등록 순서와 무관하게 생성 서비스를 연결한다")
    void wireGenerationServiceWithAdapters() {
        try (AnnotationConfigApplicationContext context = createContext(true)) {
            ClaimedComicGenerationService service = context.getBean(ClaimedComicGenerationService.class);

            assertThat(service.isAvailable()).isTrue();
        }
    }

    @Test
    @DisplayName("어댑터가 없으면 생성 서비스를 안전하게 비활성 상태로 둔다")
    void keepGenerationServiceUnavailableWithoutAdapters() {
        try (AnnotationConfigApplicationContext context = createContext(false)) {
            ClaimedComicGenerationService service = context.getBean(ClaimedComicGenerationService.class);

            assertThat(service.isAvailable()).isFalse();
        }
    }

    private AnnotationConfigApplicationContext createContext(boolean registerAdapters) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(RequestFingerprintGenerator.class, RequestFingerprintGenerator::new);
        context.registerBean(GenerationPromptRepository.class, () -> mock(GenerationPromptRepository.class));
        context.registerBean(ComicGenerationRepository.class, () -> mock(ComicGenerationRepository.class));
        context.registerBean(
                ComicGenerationCompletionService.class,
                () -> mock(ComicGenerationCompletionService.class)
        );
        if (registerAdapters) {
            context.registerBean(StoryboardGenerator.class, () -> mock(StoryboardGenerator.class));
            context.registerBean(ComicImageGenerator.class, () -> mock(ComicImageGenerator.class));
            context.registerBean(ImageStorage.class, () -> mock(ImageStorage.class));
        }
        context.register(ComicGenerationConfiguration.class);
        context.refresh();
        return context;
    }
}
