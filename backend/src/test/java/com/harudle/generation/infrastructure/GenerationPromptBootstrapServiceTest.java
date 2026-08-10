package com.harudle.generation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.generation.domain.GenerationPrompt;
import com.harudle.generation.repository.GenerationPromptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class GenerationPromptBootstrapServiceTest {

    private static final String LOCK_PROMPT_TABLE_SQL =
            "LOCK TABLE generation_prompts IN SHARE ROW EXCLUSIVE MODE";

    @Mock
    private GenerationPromptRepository generationPromptRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private GenerationPromptBootstrapService bootstrapService;

    @BeforeEach
    void setUp() {
        bootstrapService = new GenerationPromptBootstrapService(
                generationPromptRepository,
                jdbcTemplate
        );
    }

    @Test
    @DisplayName("프롬프트 테이블을 잠근 뒤 비어 있으면 초기 프롬프트를 저장한다")
    void createPromptWhenTableIsEmpty() {
        GenerationPrompt prompt = createPrompt();
        when(generationPromptRepository.count()).thenReturn(0L);
        when(generationPromptRepository.saveAndFlush(prompt)).thenReturn(prompt);

        GenerationPrompt result = bootstrapService.createIfEmpty(prompt);

        assertThat(result).isSameAs(prompt);
        verify(jdbcTemplate).execute(LOCK_PROMPT_TABLE_SQL);
    }

    @Test
    @DisplayName("잠금 획득 후 다른 인스턴스가 등록한 프롬프트가 있으면 추가하지 않는다")
    void skipPromptCreatedByAnotherInstance() {
        GenerationPrompt prompt = createPrompt();
        when(generationPromptRepository.count()).thenReturn(1L);

        GenerationPrompt result = bootstrapService.createIfEmpty(prompt);

        assertThat(result).isNull();
        verify(jdbcTemplate).execute(LOCK_PROMPT_TABLE_SQL);
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
