package com.harudle.generation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.harudle.generation.domain.ComicGeneration;
import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.domain.GenerationPrompt;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.domain.StoryPanel;
import com.harudle.generation.domain.Storyboard;
import com.harudle.generation.repository.ComicGenerationRepository;
import com.harudle.generation.repository.GenerationPromptRepository;
import com.harudle.generation.service.dto.ComicGenerationResult;
import com.harudle.generation.service.dto.GenerateComicCommand;
import com.harudle.generation.service.exception.AiGenerationErrorType;
import com.harudle.generation.service.exception.AiGenerationException;
import com.harudle.generation.service.exception.ComicGenerationFailedException;
import com.harudle.generation.service.exception.GenerationInProgressException;
import com.harudle.generation.service.exception.IdempotencyKeyConflictException;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig({GenerateComicService.class, RequestFingerprintGenerator.class})
class GenerateComicServiceTest {

    @MockitoBean
    private GenerationPromptRepository generationPromptRepository;

    @MockitoBean
    private ComicGenerationRepository comicGenerationRepository;

    @MockitoBean
    private StoryboardGenerator storyboardGenerator;

    @MockitoBean
    private ComicImageGenerator comicImageGenerator;

    @MockitoBean
    private ImageStorage imageStorage;

    @Autowired
    private GenerateComicService generateComicService;

    @Autowired
    private RequestFingerprintGenerator requestFingerprintGenerator;

    @Test
    @DisplayName("일기로 스토리보드와 이미지를 생성하고 성공 결과를 반환한다")
    void generateComic() {
        GenerateComicCommand command = createCommand();
        GenerationPrompt prompt = createPrompt();
        Storyboard storyboard = createStoryboard();
        ReferenceImage referenceImage = createReferenceImage();
        GeneratedImage generatedImage = createGeneratedImage();
        AtomicInteger saveCount = new AtomicInteger();

        when(comicGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.empty());
        when(generationPromptRepository.findFirstByOrderByIdDesc()).thenReturn(Optional.of(prompt));
        when(comicGenerationRepository.saveAndFlush(any(ComicGeneration.class)))
                .thenAnswer(invocation -> {
                    ComicGeneration generation = invocation.getArgument(0);
                    if (saveCount.getAndIncrement() == 0) {
                        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PROCESSING);
                    } else {
                        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
                    }
                    return generation;
                });
        when(storyboardGenerator.generate(any(StoryboardGenerationRequest.class))).thenReturn(storyboard);
        when(imageStorage.load(prompt.getImageAssetObjectKey())).thenReturn(referenceImage);
        when(comicImageGenerator.generate(any(ComicImageGenerationRequest.class))).thenReturn(generatedImage);
        when(imageStorage.store(generatedImage)).thenReturn("generated/comic.png");

        ComicGenerationResult result = generateComicService.generate(command);

        assertThat(result.status()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(result.title()).isEqualTo(storyboard.title());
        assertThat(result.imageObjectKey()).isEqualTo("generated/comic.png");
        assertThat(result.completedAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(result.newlyCreated()).isTrue();
        assertThat(saveCount).hasValue(2);

        InOrder inOrder = inOrder(
                generationPromptRepository,
                comicGenerationRepository,
                storyboardGenerator,
                imageStorage,
                comicImageGenerator
        );
        inOrder.verify(comicGenerationRepository).findByIdempotencyKey(command.idempotencyKey());
        inOrder.verify(generationPromptRepository).findFirstByOrderByIdDesc();
        inOrder.verify(comicGenerationRepository).saveAndFlush(any(ComicGeneration.class));
        inOrder.verify(storyboardGenerator).generate(new StoryboardGenerationRequest(
                command.diaryText(),
                prompt.getStoryboardPromptText()
        ));
        inOrder.verify(imageStorage).load(prompt.getImageAssetObjectKey());
        inOrder.verify(comicImageGenerator).generate(new ComicImageGenerationRequest(
                storyboard,
                prompt.getImageStylePromptText(),
                referenceImage
        ));
        inOrder.verify(imageStorage).store(generatedImage);
        inOrder.verify(comicGenerationRepository).saveAndFlush(any(ComicGeneration.class));
    }

    @Test
    @DisplayName("동일한 요청을 다른 작업이 먼저 선점했다면 기존 처리 상태를 반환한다")
    void handleGenerationClaimedByConcurrentRequest() {
        GenerateComicCommand command = createCommand();
        GenerationPrompt prompt = createPrompt();
        ComicGeneration concurrentGeneration = createGeneration(command);
        DataIntegrityViolationException exception = new DataIntegrityViolationException("중복 멱등성 키");

        when(comicGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concurrentGeneration));
        when(generationPromptRepository.findFirstByOrderByIdDesc()).thenReturn(Optional.of(prompt));
        when(comicGenerationRepository.saveAndFlush(any(ComicGeneration.class))).thenThrow(exception);

        assertThatThrownBy(() -> generateComicService.generate(command))
                .isInstanceOf(GenerationInProgressException.class);
        verifyNoInteractions(storyboardGenerator, comicImageGenerator, imageStorage);

        InOrder inOrder = inOrder(comicGenerationRepository, generationPromptRepository);
        inOrder.verify(comicGenerationRepository).findByIdempotencyKey(command.idempotencyKey());
        inOrder.verify(generationPromptRepository).findFirstByOrderByIdDesc();
        inOrder.verify(comicGenerationRepository).saveAndFlush(any(ComicGeneration.class));
        inOrder.verify(comicGenerationRepository).findByIdempotencyKey(command.idempotencyKey());
    }

    @Test
    @DisplayName("멱등성 키 중복이 아닌 무결성 예외는 그대로 전달한다")
    void propagateUnrelatedIntegrityViolation() {
        GenerateComicCommand command = createCommand();
        GenerationPrompt prompt = createPrompt();
        DataIntegrityViolationException exception = new DataIntegrityViolationException("다른 제약 조건 위반");

        when(comicGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.empty());
        when(generationPromptRepository.findFirstByOrderByIdDesc()).thenReturn(Optional.of(prompt));
        when(comicGenerationRepository.saveAndFlush(any(ComicGeneration.class))).thenThrow(exception);

        assertThatThrownBy(() -> generateComicService.generate(command)).isSameAs(exception);
        verifyNoInteractions(storyboardGenerator, comicImageGenerator, imageStorage);
    }

    @ParameterizedTest
    @MethodSource("aiGenerationErrorMappings")
    @DisplayName("AI 생성에 실패하면 실패 상태와 오류 코드를 저장하고 예외를 다시 던진다")
    void failGenerationWhenAiGenerationFails(
            AiGenerationErrorType errorType,
            GenerationErrorCode expectedErrorCode
    ) {
        GenerateComicCommand command = createCommand();
        GenerationPrompt prompt = createPrompt();
        AiGenerationException exception = new AiGenerationException(errorType, "AI 생성에 실패했습니다.");
        AtomicInteger saveCount = new AtomicInteger();
        AtomicReference<ComicGeneration> savedGeneration = new AtomicReference<>();

        when(comicGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.empty());
        when(generationPromptRepository.findFirstByOrderByIdDesc()).thenReturn(Optional.of(prompt));
        when(comicGenerationRepository.saveAndFlush(any(ComicGeneration.class)))
                .thenAnswer(invocation -> {
                    ComicGeneration generation = invocation.getArgument(0);
                    saveCount.incrementAndGet();
                    savedGeneration.set(generation);
                    return generation;
                });
        when(storyboardGenerator.generate(any(StoryboardGenerationRequest.class))).thenThrow(exception);

        assertThatThrownBy(() -> generateComicService.generate(command)).isSameAs(exception);
        assertThat(saveCount).hasValue(2);
        assertThat(savedGeneration.get().getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(savedGeneration.get().getErrorCode()).isEqualTo(expectedErrorCode);
        assertThat(savedGeneration.get().getCompletedAt()).isBeforeOrEqualTo(Instant.now());
        verifyNoInteractions(comicImageGenerator, imageStorage);
    }

    @Test
    @DisplayName("이미지 저장에 실패하면 실패 상태와 오류 코드를 저장하고 예외를 다시 던진다")
    void failGenerationWhenImageStorageFails() {
        GenerateComicCommand command = createCommand();
        GenerationPrompt prompt = createPrompt();
        Storyboard storyboard = createStoryboard();
        ReferenceImage referenceImage = createReferenceImage();
        GeneratedImage generatedImage = createGeneratedImage();
        ImageStorageException exception = new ImageStorageException("이미지 저장에 실패했습니다.");
        AtomicInteger saveCount = new AtomicInteger();
        AtomicReference<ComicGeneration> savedGeneration = new AtomicReference<>();

        when(comicGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.empty());
        when(generationPromptRepository.findFirstByOrderByIdDesc()).thenReturn(Optional.of(prompt));
        when(comicGenerationRepository.saveAndFlush(any(ComicGeneration.class)))
                .thenAnswer(invocation -> {
                    ComicGeneration generation = invocation.getArgument(0);
                    saveCount.incrementAndGet();
                    savedGeneration.set(generation);
                    return generation;
                });
        when(storyboardGenerator.generate(any(StoryboardGenerationRequest.class))).thenReturn(storyboard);
        when(imageStorage.load(prompt.getImageAssetObjectKey())).thenReturn(referenceImage);
        when(comicImageGenerator.generate(any(ComicImageGenerationRequest.class))).thenReturn(generatedImage);
        when(imageStorage.store(generatedImage)).thenThrow(exception);

        assertThatThrownBy(() -> generateComicService.generate(command)).isSameAs(exception);
        assertThat(saveCount).hasValue(2);
        assertThat(savedGeneration.get().getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(savedGeneration.get().getErrorCode()).isEqualTo(GenerationErrorCode.IMAGE_STORAGE_ERROR);
        assertThat(savedGeneration.get().getCompletedAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("동일한 요청이 이미 성공했다면 저장된 결과를 반환한다")
    void returnExistingSuccessfulGeneration() {
        GenerateComicCommand command = createCommand();
        Storyboard storyboard = createStoryboard();
        Instant completedAt = Instant.parse("2026-08-10T10:00:00Z");
        ComicGeneration generation = createGeneration(command);
        generation.succeed(storyboard, "generated/existing.png", completedAt);
        when(comicGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.of(generation));

        ComicGenerationResult result = generateComicService.generate(command);

        assertThat(result.generationId()).isEqualTo(generation.getId());
        assertThat(result.status()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(result.title()).isEqualTo(storyboard.title());
        assertThat(result.imageObjectKey()).isEqualTo("generated/existing.png");
        assertThat(result.completedAt()).isEqualTo(completedAt);
        assertThat(result.newlyCreated()).isFalse();
        verifyOnlyIdempotencyLookup(command);
    }

    @Test
    @DisplayName("동일한 멱등성 키를 다른 요청에 사용하면 예외가 발생한다")
    void rejectReusedKeyForDifferentRequest() {
        GenerateComicCommand command = createCommand();
        String requestFingerprint = requestFingerprintGenerator.generate(command);
        String differentFingerprint = createDifferentFingerprint(requestFingerprint);
        ComicGeneration generation = ComicGeneration.start(
                command.diaryId(),
                1L,
                command.idempotencyKey(),
                differentFingerprint
        );
        when(comicGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.of(generation));

        assertThatThrownBy(() -> generateComicService.generate(command))
                .isInstanceOf(IdempotencyKeyConflictException.class)
                .hasMessageContaining("멱등성 키");
        verifyOnlyIdempotencyLookup(command);
    }

    @Test
    @DisplayName("동일한 요청이 처리 중이라면 예외가 발생한다")
    void rejectProcessingGeneration() {
        GenerateComicCommand command = createCommand();
        ComicGeneration generation = createGeneration(command);
        when(comicGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.of(generation));

        assertThatThrownBy(() -> generateComicService.generate(command))
                .isInstanceOf(GenerationInProgressException.class)
                .hasMessageContaining("처리 중");
        verifyOnlyIdempotencyLookup(command);
    }

    @Test
    @DisplayName("동일한 요청이 이미 실패했다면 저장된 오류 코드와 함께 예외가 발생한다")
    void rejectFailedGeneration() {
        GenerateComicCommand command = createCommand();
        ComicGeneration generation = createGeneration(command);
        generation.fail(GenerationErrorCode.AI_PROVIDER_TIMEOUT, Instant.now());
        when(comicGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.of(generation));

        assertThatThrownBy(() -> generateComicService.generate(command))
                .isInstanceOfSatisfying(
                        ComicGenerationFailedException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(GenerationErrorCode.AI_PROVIDER_TIMEOUT)
                );
        verifyOnlyIdempotencyLookup(command);
    }

    private GenerateComicCommand createCommand() {
        return new GenerateComicCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocalDate.of(2026, 8, 10),
                "오늘 친구와 카페에 갔다.",
                UUID.randomUUID()
        );
    }

    private static Stream<Arguments> aiGenerationErrorMappings() {
        return Stream.of(
                Arguments.of(AiGenerationErrorType.PROVIDER_ERROR, GenerationErrorCode.AI_PROVIDER_ERROR),
                Arguments.of(AiGenerationErrorType.TIMEOUT, GenerationErrorCode.AI_PROVIDER_TIMEOUT)
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

    private String createDifferentFingerprint(String requestFingerprint) {
        String firstCharacter = requestFingerprint.startsWith("0") ? "1" : "0";
        return firstCharacter + requestFingerprint.substring(1);
    }

    private GenerationPrompt createPrompt() {
        GenerationPrompt prompt = mock(GenerationPrompt.class);
        when(prompt.getId()).thenReturn(1L);
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
        return new StoryPanel(
                panelNumber,
                caption,
                "장면 " + panelNumber,
                "등장인물 " + panelNumber,
                "감정 " + panelNumber,
                List.of("소품 " + panelNumber)
        );
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

    private void verifyOnlyIdempotencyLookup(GenerateComicCommand command) {
        verify(comicGenerationRepository).findByIdempotencyKey(command.idempotencyKey());
        verifyNoMoreInteractions(comicGenerationRepository);
        verifyNoInteractions(
                generationPromptRepository,
                storyboardGenerator,
                comicImageGenerator,
                imageStorage
        );
    }
}
