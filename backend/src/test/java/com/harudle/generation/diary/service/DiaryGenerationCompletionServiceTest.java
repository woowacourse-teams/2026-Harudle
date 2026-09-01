package com.harudle.generation.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.domain.StoryPanel;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.exception.DiaryGenerationFailedException;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DiaryGenerationCompletionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");

    @Mock
    private DiaryGenerationRepository diaryGenerationRepository;

    @Mock
    private DiaryRepository diaryRepository;

    private DiaryGenerationCompletionService completionService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        completionService = new DiaryGenerationCompletionService(
                diaryGenerationRepository,
                diaryRepository,
                clock
        );
    }

    @Test
    @DisplayName("행 잠금으로 조회한 처리 중 생성을 성공 상태로 완료한다")
    void succeedProcessingGeneration() {
        DiaryGeneration generation = createGeneration();
        Storyboard storyboard = createStoryboard();
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId()))
                .thenReturn(Optional.of(generation));

        DiaryGeneration result = completionService.succeed(
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
        DiaryGeneration generation = createGeneration();
        generation.fail(GenerationErrorCode.GENERATION_INTERRUPTED, NOW.minusSeconds(1));
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId()))
                .thenReturn(Optional.of(generation));

        assertThatThrownBy(() -> completionService.succeed(
                generation.getId(),
                createStoryboard(),
                "generated/comic.png"
        )).isInstanceOfSatisfying(
                DiaryGenerationFailedException.class,
                exception -> assertThat(exception.errorCode())
                        .isEqualTo(GenerationErrorCode.GENERATION_INTERRUPTED)
        );
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.FAILED);
    }

    @Test
    @DisplayName("이미 성공한 생성을 늦게 도착한 성공 결과가 덮어쓰지 않는다")
    void succeedKeepsExistingSuccessfulGeneration() {
        DiaryGeneration generation = createGeneration();
        Instant firstCompletedAt = NOW.minusSeconds(1);
        generation.succeed(createStoryboard(), "generated/winner.png", firstCompletedAt);
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId()))
                .thenReturn(Optional.of(generation));

        DiaryGeneration result = completionService.succeed(
                generation.getId(),
                createStoryboard(),
                "generated/loser.png"
        );

        assertThat(result).isSameAs(generation);
        assertThat(result.getImageObjectKey()).isEqualTo("generated/winner.png");
        assertThat(result.getCompletedAt()).isEqualTo(firstCompletedAt);
    }

    @Test
    @DisplayName("처리 중 생성을 실패 상태로 바꾸며 일기를 함께 폐기한다")
    void failProcessingGenerationAndDiscardDiary() {
        DiaryGeneration generation = createGeneration();
        Diary diary = mock(Diary.class);
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId()))
                .thenReturn(Optional.of(generation));
        when(diaryRepository.findByIdIncludingDeletedForUpdate(generation.getDiaryId()))
                .thenReturn(Optional.of(diary));

        GenerationErrorCode result = completionService.fail(
                generation.getId(),
                GenerationErrorCode.AI_PROVIDER_TIMEOUT
        );

        assertThat(result).isEqualTo(GenerationErrorCode.AI_PROVIDER_TIMEOUT);
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(generation.getCompletedAt()).isEqualTo(NOW);
        verify(diary).delete(NOW);
    }

    @Test
    @DisplayName("이미 실패한 생성의 오류 코드를 유지한다")
    void failKeepsExistingErrorCode() {
        DiaryGeneration generation = createGeneration();
        Instant failedAt = NOW.minusSeconds(1);
        generation.fail(GenerationErrorCode.GENERATION_INTERRUPTED, failedAt);
        Diary diary = mock(Diary.class);
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId()))
                .thenReturn(Optional.of(generation));
        when(diaryRepository.findByIdIncludingDeletedForUpdate(generation.getDiaryId()))
                .thenReturn(Optional.of(diary));

        GenerationErrorCode result = completionService.fail(
                generation.getId(),
                GenerationErrorCode.AI_PROVIDER_TIMEOUT
        );

        assertThat(result).isEqualTo(GenerationErrorCode.GENERATION_INTERRUPTED);
        assertThat(generation.getErrorCode()).isEqualTo(GenerationErrorCode.GENERATION_INTERRUPTED);
        verify(diary).delete(failedAt);
    }

    @Test
    @DisplayName("처리 제한 시간을 지난 생성을 중단하며 일기를 함께 폐기한다")
    void interruptStaleGenerationAndDiscardDiary() {
        DiaryGeneration generation = createGeneration();
        Duration processingTimeout = Duration.ofMinutes(15);
        ReflectionTestUtils.setField(
                generation,
                "updatedAt",
                NOW.minus(processingTimeout).minusSeconds(1)
        );
        Diary diary = mock(Diary.class);
        when(diaryGenerationRepository.findByIdForUpdate(generation.getId()))
                .thenReturn(Optional.of(generation));
        when(diaryRepository.findByIdIncludingDeletedForUpdate(generation.getDiaryId()))
                .thenReturn(Optional.of(diary));

        boolean interrupted = completionService.interruptIfStale(
                generation.getId(),
                NOW,
                processingTimeout
        );

        assertThat(interrupted).isTrue();
        assertThat(generation.getErrorCode())
                .isEqualTo(GenerationErrorCode.GENERATION_INTERRUPTED);
        verify(diary).delete(NOW);
    }

    private DiaryGeneration createGeneration() {
        return DiaryGeneration.start(
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
