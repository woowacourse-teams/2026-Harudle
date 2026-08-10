package com.harudle.generation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.harudle.generation.domain.ComicGeneration;
import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.domain.StoryPanel;
import com.harudle.generation.domain.Storyboard;
import com.harudle.generation.repository.ComicGenerationRepository;
import com.harudle.generation.service.exception.ComicGenerationFailedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ComicGenerationCompletionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");

    @Mock
    private ComicGenerationRepository comicGenerationRepository;

    private ComicGenerationCompletionService completionService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        completionService = new ComicGenerationCompletionService(comicGenerationRepository, clock);
    }

    @Test
    @DisplayName("행 잠금으로 조회한 처리 중 생성을 성공 상태로 완료한다")
    void succeedProcessingGeneration() {
        ComicGeneration generation = createGeneration();
        Storyboard storyboard = createStoryboard();
        when(comicGenerationRepository.findByIdForUpdate(generation.getId()))
                .thenReturn(Optional.of(generation));

        ComicGeneration result = completionService.succeed(
                generation.getId(),
                storyboard,
                "generated/comic.png"
        );

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(result.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("이미 실패한 생성을 늦게 도착한 성공 결과가 덮어쓸 수 없다")
    void succeedDoesNotOverwriteFailedGeneration() {
        ComicGeneration generation = createGeneration();
        generation.fail(GenerationErrorCode.GENERATION_INTERRUPTED, NOW.minusSeconds(1));
        when(comicGenerationRepository.findByIdForUpdate(generation.getId()))
                .thenReturn(Optional.of(generation));

        assertThatThrownBy(() -> completionService.succeed(
                generation.getId(),
                createStoryboard(),
                "generated/comic.png"
        )).isInstanceOfSatisfying(
                ComicGenerationFailedException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(GenerationErrorCode.GENERATION_INTERRUPTED)
        );
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.FAILED);
    }

    @Test
    @DisplayName("이미 실패한 생성의 오류 코드를 유지한다")
    void failKeepsExistingErrorCode() {
        ComicGeneration generation = createGeneration();
        generation.fail(GenerationErrorCode.GENERATION_INTERRUPTED, NOW.minusSeconds(1));
        when(comicGenerationRepository.findByIdForUpdate(generation.getId()))
                .thenReturn(Optional.of(generation));

        GenerationErrorCode result = completionService.fail(
                generation.getId(),
                GenerationErrorCode.AI_PROVIDER_TIMEOUT
        );

        assertThat(result).isEqualTo(GenerationErrorCode.GENERATION_INTERRUPTED);
        assertThat(generation.getErrorCode()).isEqualTo(GenerationErrorCode.GENERATION_INTERRUPTED);
    }

    private ComicGeneration createGeneration() {
        return ComicGeneration.start(
                UUID.randomUUID(),
                1L,
                UUID.randomUUID(),
                "a".repeat(64)
        );
    }

    private Storyboard createStoryboard() {
        return new Storyboard(
                "친구와 보낸 하루",
                "같은 주인공이 모든 패널에 등장한다.",
                List.of(
                        createPanel(1, "첫 번째 캡션"),
                        createPanel(2, "두 번째 캡션"),
                        createPanel(3, "세 번째 캡션"),
                        createPanel(4, "네 번째 캡션")
                )
        );
    }

    private StoryPanel createPanel(int panelNumber, String caption) {
        return new StoryPanel(panelNumber, caption, "장면", "등장인물", "감정", List.of());
    }
}
