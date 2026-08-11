package com.harudle.generation.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.generation.config.GenerationPromptBootstrapProperties;
import com.harudle.generation.domain.GenerationPrompt;
import com.harudle.generation.repository.GenerationPromptRepository;
import com.harudle.generation.service.port.ImageStorage;
import com.harudle.generation.service.port.ImageStorageException;
import com.harudle.generation.service.port.ReferenceImage;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

@ExtendWith(MockitoExtension.class)
class GenerationPromptInitializerTest {

    @Mock
    private GenerationPromptRepository generationPromptRepository;

    @Mock
    private ImageStorage imageStorage;

    @Mock
    private GenerationPromptBootstrapService bootstrapService;

    @Mock
    private ApplicationArguments applicationArguments;

    @Mock
    private ReferenceImage referenceImage;

    private GenerationPromptBootstrapProperties properties;

    @BeforeEach
    void setUp() {
        properties = new GenerationPromptBootstrapProperties(
                true,
                "스토리보드 프롬프트",
                "이미지 스타일 프롬프트",
                "references/style.png"
        );
    }

    @Test
    @DisplayName("빈 테이블이면 참조 이미지를 검증한 뒤 초기 프롬프트를 등록한다")
    void initializePromptWhenTableIsEmpty() {
        GenerationPromptInitializer initializer = createInitializer(properties);
        when(generationPromptRepository.count()).thenReturn(0L);
        when(imageStorage.load("references/style.png")).thenReturn(referenceImage);
        when(bootstrapService.createIfEmpty(any(GenerationPrompt.class)))
                .thenReturn(Optional.empty());

        initializer.run(applicationArguments);

        verify(bootstrapService).createIfEmpty(any(GenerationPrompt.class));
    }

    @Test
    @DisplayName("기존 프롬프트가 있으면 설정과 참조 이미지를 확인하지 않는다")
    void skipInitializationWhenPromptExists() {
        GenerationPromptInitializer initializer = createInitializer(properties);
        when(generationPromptRepository.count()).thenReturn(1L);

        initializer.run(applicationArguments);

        verifyNoInteractions(imageStorage, bootstrapService);
    }

    @Test
    @DisplayName("초기화 설정이 불완전하면 애플리케이션 시작을 중단한다")
    void rejectIncompletePromptConfiguration() {
        GenerationPromptBootstrapProperties incompleteProperties = new GenerationPromptBootstrapProperties(
                true,
                "",
                "이미지 스타일 프롬프트",
                "references/style.png"
        );
        GenerationPromptInitializer initializer = createInitializer(incompleteProperties);
        when(generationPromptRepository.count()).thenReturn(0L);

        assertThatThrownBy(() -> initializer.run(applicationArguments))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("스토리보드 프롬프트");
        verifyNoInteractions(imageStorage, bootstrapService);
    }

    @Test
    @DisplayName("참조 이미지를 읽을 수 없으면 프롬프트를 등록하지 않는다")
    void rejectUnavailableReferenceImage() {
        GenerationPromptInitializer initializer = createInitializer(properties);
        ImageStorageException exception = new ImageStorageException("참조 이미지를 읽을 수 없습니다.");
        when(generationPromptRepository.count()).thenReturn(0L);
        when(imageStorage.load("references/style.png")).thenThrow(exception);

        assertThatThrownBy(() -> initializer.run(applicationArguments)).isSameAs(exception);
        verify(bootstrapService, never()).createIfEmpty(any());
    }

    private GenerationPromptInitializer createInitializer(
            GenerationPromptBootstrapProperties bootstrapProperties
    ) {
        return new GenerationPromptInitializer(
                bootstrapProperties,
                generationPromptRepository,
                imageStorage,
                bootstrapService
        );
    }
}
