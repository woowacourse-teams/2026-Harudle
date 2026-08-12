package com.harudle.generation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.generation.domain.ComicGeneration;
import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.domain.GenerationPrompt;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.domain.StoryPanel;
import com.harudle.generation.domain.Storyboard;
import com.harudle.generation.repository.ComicGenerationRepository;
import com.harudle.generation.repository.GenerationPromptRepository;
import com.harudle.generation.service.dto.CompletedComicGeneration;
import com.harudle.generation.service.dto.GenerateComicCommand;
import com.harudle.generation.service.exception.AiGenerationErrorType;
import com.harudle.generation.service.exception.AiGenerationException;
import com.harudle.generation.service.exception.ComicGenerationFailedException;
import com.harudle.generation.service.port.ComicImageGenerationRequest;
import com.harudle.generation.service.port.ComicImageGenerator;
import com.harudle.generation.service.port.GeneratedImage;
import com.harudle.generation.service.port.ImageStorage;
import com.harudle.generation.service.port.ImageStorageException;
import com.harudle.generation.service.port.ReferenceImage;
import com.harudle.generation.service.port.StoryboardGenerationRequest;
import com.harudle.generation.service.port.StoryboardGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

@ExtendWith(MockitoExtension.class)
class ClaimedComicGenerationServiceTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID DIARY_ID = UUID.fromString("6b66acba-0136-4822-8a59-f355dd7c977d");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("7e5cc251-fdde-4cc0-a54e-2c8142750609");

    @Mock
    private GenerationPromptRepository generationPromptRepository;

    @Mock
    private ComicGenerationRepository comicGenerationRepository;

    @Mock
    private StoryboardGenerator storyboardGenerator;

    @Mock
    private ComicImageGenerator comicImageGenerator;

    @Mock
    private ImageStorage imageStorage;

    @Mock
    private ComicGenerationCompletionService completionService;

    private RequestFingerprintGenerator requestFingerprintGenerator;
    private ClaimedComicGenerationService generationService;

    @BeforeEach
    void setUp() {
        requestFingerprintGenerator = new RequestFingerprintGenerator();
        generationService = new ClaimedComicGenerationService(
                requestFingerprintGenerator,
                generationPromptRepository,
                comicGenerationRepository,
                storyboardGenerator,
                comicImageGenerator,
                imageStorage,
                completionService
        );
    }

    @Test
    @DisplayName("선점 트랜잭션이 끝난 생성 요청을 외부 시스템에서 처리하고 완료한다")
    void generateClaimedComic() {
        GenerateComicCommand command = createCommand();
        ComicGeneration generation = createGeneration(command);
        GenerationPrompt prompt = createPrompt();
        Storyboard storyboard = createStoryboard();
        ReferenceImage referenceImage = createReferenceImage();
        GeneratedImage generatedImage = createGeneratedImage();
        ComicGeneration completedGeneration = createGeneration(command);
        completedGeneration.succeed(storyboard, "generated/comic.png", Instant.parse("2026-08-06T12:00:00Z"));
        when(comicGenerationRepository.findById(generation.getId())).thenReturn(Optional.of(generation));
        when(generationPromptRepository.findById(1L)).thenReturn(Optional.of(prompt));
        when(storyboardGenerator.generate(any(StoryboardGenerationRequest.class))).thenReturn(storyboard);
        when(imageStorage.load("references/style.png")).thenReturn(referenceImage);
        when(comicImageGenerator.generate(any(ComicImageGenerationRequest.class))).thenReturn(generatedImage);
        when(imageStorage.store(generatedImage)).thenReturn("generated/comic.png");
        when(completionService.succeed(generation.getId(), storyboard, "generated/comic.png"))
                .thenReturn(completedGeneration);

        CompletedComicGeneration result = generationService.generate(command, generation.getId());

        assertThat(result.title()).isEqualTo("친구와 보낸 하루");
        verify(imageStorage, never()).delete(any(String.class));
    }

    @Test
    @DisplayName("다른 실행이 먼저 완료했으면 이번 실행이 저장한 이미지만 폐기한다")
    void deleteImageDiscardedByConcurrentCompletion() {
        GenerateComicCommand command = createCommand();
        ComicGeneration generation = createGeneration(command);
        GenerationPrompt prompt = createPrompt();
        Storyboard storyboard = createStoryboard();
        ReferenceImage referenceImage = createReferenceImage();
        GeneratedImage generatedImage = createGeneratedImage();
        ComicGeneration winningGeneration = createGeneration(command);
        winningGeneration.succeed(
                storyboard,
                "generated/winner.png",
                Instant.parse("2026-08-06T12:00:00Z")
        );
        when(comicGenerationRepository.findById(generation.getId())).thenReturn(Optional.of(generation));
        when(generationPromptRepository.findById(1L)).thenReturn(Optional.of(prompt));
        when(storyboardGenerator.generate(any(StoryboardGenerationRequest.class))).thenReturn(storyboard);
        when(imageStorage.load("references/style.png")).thenReturn(referenceImage);
        when(comicImageGenerator.generate(any(ComicImageGenerationRequest.class))).thenReturn(generatedImage);
        when(imageStorage.store(generatedImage)).thenReturn("generated/loser.png");
        when(completionService.succeed(generation.getId(), storyboard, "generated/loser.png"))
                .thenReturn(winningGeneration);

        CompletedComicGeneration result = generationService.generate(command, generation.getId());

        assertThat(result.imageObjectKey()).isEqualTo("generated/winner.png");
        verify(imageStorage).delete("generated/loser.png");
    }

    @Test
    @DisplayName("AI 공급자 호출이 실패하면 생성 기록을 실패 처리한다")
    void generateClaimedComicMarksAiFailure() {
        GenerateComicCommand command = createCommand();
        ComicGeneration generation = createGeneration(command);
        GenerationPrompt prompt = mock(GenerationPrompt.class);
        AiGenerationException exception = new AiGenerationException(
                AiGenerationErrorType.TIMEOUT,
                "AI 공급자 응답 시간이 초과되었습니다."
        );
        when(prompt.getStoryboardPromptText()).thenReturn("스토리보드 프롬프트");
        when(comicGenerationRepository.findById(generation.getId())).thenReturn(Optional.of(generation));
        when(generationPromptRepository.findById(1L)).thenReturn(Optional.of(prompt));
        when(storyboardGenerator.generate(any(StoryboardGenerationRequest.class))).thenThrow(exception);
        when(completionService.fail(generation.getId(), GenerationErrorCode.AI_PROVIDER_TIMEOUT))
                .thenReturn(GenerationErrorCode.AI_PROVIDER_TIMEOUT);

        assertThatThrownBy(() -> generationService.generate(command, generation.getId()))
                .isSameAs(exception);
        verify(completionService).fail(generation.getId(), GenerationErrorCode.AI_PROVIDER_TIMEOUT);
    }

    @Test
    @DisplayName("오래된 생성 복구와 AI 실패가 경합하면 저장된 오류를 반환한다")
    void generateClaimedComicKeepsInterruptedError() {
        GenerateComicCommand command = createCommand();
        ComicGeneration generation = createGeneration(command);
        GenerationPrompt prompt = mock(GenerationPrompt.class);
        AiGenerationException exception = new AiGenerationException(
                AiGenerationErrorType.TIMEOUT,
                "AI 공급자 응답 시간이 초과되었습니다."
        );
        when(prompt.getStoryboardPromptText()).thenReturn("스토리보드 프롬프트");
        when(comicGenerationRepository.findById(generation.getId())).thenReturn(Optional.of(generation));
        when(generationPromptRepository.findById(1L)).thenReturn(Optional.of(prompt));
        when(storyboardGenerator.generate(any(StoryboardGenerationRequest.class))).thenThrow(exception);
        when(completionService.fail(generation.getId(), GenerationErrorCode.AI_PROVIDER_TIMEOUT))
                .thenReturn(GenerationErrorCode.GENERATION_INTERRUPTED);

        assertThatThrownBy(() -> generationService.generate(command, generation.getId()))
                .isInstanceOfSatisfying(
                        ComicGenerationFailedException.class,
                        failure -> assertThat(failure.errorCode())
                                .isEqualTo(GenerationErrorCode.GENERATION_INTERRUPTED)
                );
    }

    @Test
    @DisplayName("오래된 생성 복구와 완료가 경합하면 저장 이미지를 삭제한다")
    void generateClaimedComicDeletesImageAfterInterruptedCompletion() {
        GenerateComicCommand command = createCommand();
        ComicGeneration generation = createGeneration(command);
        GenerationPrompt prompt = createPrompt();
        Storyboard storyboard = createStoryboard();
        ReferenceImage referenceImage = createReferenceImage();
        GeneratedImage generatedImage = createGeneratedImage();
        ComicGenerationFailedException exception = new ComicGenerationFailedException(
                GenerationErrorCode.GENERATION_INTERRUPTED
        );
        when(comicGenerationRepository.findById(generation.getId()))
                .thenReturn(Optional.of(generation))
                .thenAnswer(invocation -> {
                    generation.fail(
                            GenerationErrorCode.GENERATION_INTERRUPTED,
                            Instant.parse("2026-08-06T12:00:00Z")
                    );
                    return Optional.of(generation);
                });
        when(generationPromptRepository.findById(1L)).thenReturn(Optional.of(prompt));
        when(storyboardGenerator.generate(any(StoryboardGenerationRequest.class))).thenReturn(storyboard);
        when(imageStorage.load("references/style.png")).thenReturn(referenceImage);
        when(comicImageGenerator.generate(any(ComicImageGenerationRequest.class))).thenReturn(generatedImage);
        when(imageStorage.store(generatedImage)).thenReturn("generated/comic.png");
        when(completionService.succeed(generation.getId(), storyboard, "generated/comic.png"))
                .thenThrow(exception);

        assertThatThrownBy(() -> generationService.generate(command, generation.getId()))
                .isSameAs(exception);
        verify(imageStorage).delete("generated/comic.png");
    }

    @Test
    @DisplayName("다른 완료 트랜잭션이 처리 중이면 이미지 삭제를 보류한다")
    void keepImageWhileConcurrentCompletionIsProcessing() {
        GenerateComicCommand command = createCommand();
        ComicGeneration generation = createGeneration(command);
        GenerationPrompt prompt = createPrompt();
        Storyboard storyboard = createStoryboard();
        ReferenceImage referenceImage = createReferenceImage();
        GeneratedImage generatedImage = createGeneratedImage();
        IllegalStateException completionException = new IllegalStateException("완료 결과 확인 실패");
        when(comicGenerationRepository.findById(generation.getId()))
                .thenReturn(Optional.of(generation));
        when(generationPromptRepository.findById(1L)).thenReturn(Optional.of(prompt));
        when(storyboardGenerator.generate(any(StoryboardGenerationRequest.class))).thenReturn(storyboard);
        when(imageStorage.load("references/style.png")).thenReturn(referenceImage);
        when(comicImageGenerator.generate(any(ComicImageGenerationRequest.class))).thenReturn(generatedImage);
        when(imageStorage.store(generatedImage)).thenReturn("generated/comic.png");
        when(completionService.succeed(generation.getId(), storyboard, "generated/comic.png"))
                .thenThrow(completionException);

        assertThatThrownBy(() -> generationService.generate(command, generation.getId()))
                .isSameAs(completionException);
        verify(imageStorage, never()).delete(any(String.class));
    }

    @Test
    @DisplayName("완료 결과가 불명확해도 DB가 참조하는 성공 이미지는 삭제하지 않는다")
    void keepImageReferencedByCommittedGeneration() {
        GenerateComicCommand command = createCommand();
        ComicGeneration generation = createGeneration(command);
        GenerationPrompt prompt = createPrompt();
        Storyboard storyboard = createStoryboard();
        ReferenceImage referenceImage = createReferenceImage();
        GeneratedImage generatedImage = createGeneratedImage();
        IllegalStateException completionException = new IllegalStateException("완료 결과 확인 실패");
        when(comicGenerationRepository.findById(generation.getId()))
                .thenReturn(Optional.of(generation))
                .thenAnswer(invocation -> {
                    generation.succeed(
                            storyboard,
                            "generated/comic.png",
                            Instant.parse("2026-08-06T12:00:00Z")
                    );
                    return Optional.of(generation);
                });
        when(generationPromptRepository.findById(1L)).thenReturn(Optional.of(prompt));
        when(storyboardGenerator.generate(any(StoryboardGenerationRequest.class))).thenReturn(storyboard);
        when(imageStorage.load("references/style.png")).thenReturn(referenceImage);
        when(comicImageGenerator.generate(any(ComicImageGenerationRequest.class))).thenReturn(generatedImage);
        when(imageStorage.store(generatedImage)).thenReturn("generated/comic.png");
        when(completionService.succeed(generation.getId(), storyboard, "generated/comic.png"))
                .thenThrow(completionException);

        assertThatThrownBy(() -> generationService.generate(command, generation.getId()))
                .isSameAs(completionException);
        verify(imageStorage, never()).delete(any(String.class));
    }

    @Test
    @DisplayName("완료 상태를 재확인할 수 없으면 이미지 삭제를 보류한다")
    void keepImageWhenCompletionStateCannotBeVerified() {
        GenerateComicCommand command = createCommand();
        ComicGeneration generation = createGeneration(command);
        GenerationPrompt prompt = createPrompt();
        Storyboard storyboard = createStoryboard();
        ReferenceImage referenceImage = createReferenceImage();
        GeneratedImage generatedImage = createGeneratedImage();
        IllegalStateException completionException = new IllegalStateException("완료 결과 확인 실패");
        IllegalStateException verificationException = new IllegalStateException("저장 상태 조회 실패");
        when(comicGenerationRepository.findById(generation.getId()))
                .thenReturn(Optional.of(generation))
                .thenThrow(verificationException);
        when(generationPromptRepository.findById(1L)).thenReturn(Optional.of(prompt));
        when(storyboardGenerator.generate(any(StoryboardGenerationRequest.class))).thenReturn(storyboard);
        when(imageStorage.load("references/style.png")).thenReturn(referenceImage);
        when(comicImageGenerator.generate(any(ComicImageGenerationRequest.class))).thenReturn(generatedImage);
        when(imageStorage.store(generatedImage)).thenReturn("generated/comic.png");
        when(completionService.succeed(generation.getId(), storyboard, "generated/comic.png"))
                .thenThrow(completionException);

        assertThatThrownBy(() -> generationService.generate(command, generation.getId()))
                .isSameAs(completionException)
                .satisfies(exception -> assertThat(exception.getSuppressed())
                        .containsExactly(verificationException));
        verify(imageStorage, never()).delete(any(String.class));
    }

    @Test
    @DisplayName("저장소가 공백 이미지 키를 반환하면 생성 기록과 저장 이미지를 정리한다")
    void generateClaimedComicDeletesImageWithInvalidObjectKey() {
        GenerateComicCommand command = createCommand();
        ComicGeneration generation = createGeneration(command);
        GenerationPrompt prompt = createPrompt();
        Storyboard storyboard = createStoryboard();
        ReferenceImage referenceImage = createReferenceImage();
        GeneratedImage generatedImage = createGeneratedImage();
        String invalidObjectKey = "   ";
        when(comicGenerationRepository.findById(generation.getId())).thenReturn(Optional.of(generation));
        when(generationPromptRepository.findById(1L)).thenReturn(Optional.of(prompt));
        when(storyboardGenerator.generate(any(StoryboardGenerationRequest.class))).thenReturn(storyboard);
        when(imageStorage.load("references/style.png")).thenReturn(referenceImage);
        when(comicImageGenerator.generate(any(ComicImageGenerationRequest.class))).thenReturn(generatedImage);
        when(imageStorage.store(generatedImage)).thenReturn(invalidObjectKey);
        when(completionService.fail(generation.getId(), GenerationErrorCode.IMAGE_STORAGE_ERROR))
                .thenReturn(GenerationErrorCode.IMAGE_STORAGE_ERROR);

        assertThatThrownBy(() -> generationService.generate(command, generation.getId()))
                .isInstanceOf(ImageStorageException.class);
        verify(imageStorage).delete(invalidObjectKey);
    }

    private GenerateComicCommand createCommand() {
        return new GenerateComicCommand(
                USER_ID,
                DIARY_ID,
                LocalDate.of(2026, 8, 6),
                "오늘 친구와 카페에 갔다.",
                IDEMPOTENCY_KEY
        );
    }

    private ComicGeneration createGeneration(GenerateComicCommand command) {
        return ComicGeneration.start(
                command.diaryId(),
                1L,
                command.idempotencyKey(),
                requestFingerprintGenerator.generate(command)
        );
    }

    private GenerationPrompt createPrompt() {
        GenerationPrompt prompt = mock(GenerationPrompt.class);
        when(prompt.getStoryboardPromptText()).thenReturn("스토리보드 프롬프트");
        when(prompt.getImageStylePromptText()).thenReturn("이미지 스타일 프롬프트");
        when(prompt.getImageAssetObjectKey()).thenReturn("references/style.png");
        return prompt;
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

    private ReferenceImage createReferenceImage() {
        return new ReferenceImage(
                new ByteArrayResource("reference".getBytes(StandardCharsets.UTF_8)),
                MediaType.IMAGE_PNG
        );
    }

    private GeneratedImage createGeneratedImage() {
        return new GeneratedImage(
                new ByteArrayResource("generated".getBytes(StandardCharsets.UTF_8)),
                MediaType.IMAGE_PNG
        );
    }
}
