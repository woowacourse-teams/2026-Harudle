package com.harudle.generation.prompt.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.generation.prompt.domain.GenerationPrompt;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GenerationPromptBootstrapServiceTest {

    @Mock
    private GenerationPromptRepository generationPromptRepository;

    private GenerationPromptBootstrapService bootstrapService;

    @BeforeEach
    void setUp() {
        bootstrapService = new GenerationPromptBootstrapService(generationPromptRepository);
    }

    @Test
    @DisplayName("프롬프트 테이블을 잠근 뒤 비어 있으면 초기 프롬프트를 저장한다")
    void createPromptWhenTableIsEmpty() {
        GenerationPrompt prompt = createPrompt();
        when(generationPromptRepository.count()).thenReturn(0L);
        when(generationPromptRepository.saveAndFlush(prompt)).thenReturn(prompt);

        Optional<GenerationPrompt> result = bootstrapService.createIfEmpty(prompt);

        assertThat(result).containsSame(prompt);
        verify(generationPromptRepository).lockTableForBootstrap();
    }

    @Test
    @DisplayName("잠금 획득 후 다른 인스턴스가 등록한 프롬프트가 있으면 추가하지 않는다")
    void skipPromptCreatedByAnotherInstance() {
        GenerationPrompt prompt = createPrompt();
        when(generationPromptRepository.count()).thenReturn(1L);

        Optional<GenerationPrompt> result = bootstrapService.createIfEmpty(prompt);

        assertThat(result).isEmpty();
        verify(generationPromptRepository).lockTableForBootstrap();
        verify(generationPromptRepository, never()).saveAndFlush(prompt);
    }

    private GenerationPrompt createPrompt() {
        return new GenerationPrompt(
                "스토리보드 프롬프트",
                "이미지 스타일 프롬프트",
                "references/style.png"
        );
    }
}
