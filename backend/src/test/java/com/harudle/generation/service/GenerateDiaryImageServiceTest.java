package com.harudle.generation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.harudle.generation.configuration.GenerationLifecycleProperties;
import com.harudle.generation.domain.DiaryGeneration;
import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.domain.GenerationPrompt;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.domain.StoryPanel;
import com.harudle.generation.domain.Storyboard;
import com.harudle.generation.repository.DiaryGenerationRepository;
import com.harudle.generation.repository.GenerationPromptRepository;
import com.harudle.generation.service.dto.DiaryGenerationResult;
import com.harudle.generation.service.dto.GenerateDiaryImageCommand;
import com.harudle.generation.service.exception.AiGenerationErrorType;
import com.harudle.generation.service.exception.AiGenerationException;
import com.harudle.generation.service.exception.DiaryGenerationFailedException;
import com.harudle.generation.service.exception.GenerationInProgressException;
import com.harudle.generation.service.exception.IdempotencyKeyConflictException;
import com.harudle.generation.service.port.DiaryImageGenerationRequest;
import com.harudle.generation.service.port.DiaryImageGenerator;
import com.harudle.generation.service.port.GeneratedImage;
import com.harudle.generation.service.port.ImageStorage;
import com.harudle.generation.service.port.ImageStorageException;
import com.harudle.generation.service.port.ReferenceImage;
import com.harudle.generation.service.port.StoryboardGenerationRequest;
import com.harudle.generation.service.port.StoryboardGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.util.ReflectionTestUtils;

@SpringJUnitConfig({GenerateDiaryImageService.class, RequestFingerprintGenerator.class})
class GenerateDiaryImageServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T03:00:00Z");
    private static final Duration PROCESSING_TIMEOUT = Duration.ofMinutes(30);

    @MockitoBean
    private GenerationLifecycleProperties generationLifecycleProperties;

    @MockitoBean
    private Clock clock;

    @MockitoBean
    private GenerationPromptRepository generationPromptRepository;

    @MockitoBean
    private DiaryGenerationRepository diaryGenerationRepository;

    @MockitoBean
    private StoryboardGenerator storyboardGenerator;

    @MockitoBean
    private DiaryImageGenerator diaryImageGenerator;

    @MockitoBean
    private ImageStorage imageStorage;

    @Autowired
    private GenerateDiaryImageService generateDiaryImageService;

    @Autowired
    private RequestFingerprintGenerator requestFingerprintGenerator;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(generationLifecycleProperties.processingTimeout()).thenReturn(PROCESSING_TIMEOUT);
    }

    @Test
    @DisplayName("일기로 스토리보드와 이미지를 생성하고 성공 결과를 반환한다")
    void generateDiaryImage() {
        GenerateDiaryImageCommand command = createCommand();
        GenerationPrompt prompt = createPrompt();
        Storyboard storyboard = createStoryboard();
        ReferenceImage referenceImage = createReferenceImage();
        GeneratedImage generatedImage = createGeneratedImage();
        AtomicInteger saveCount = new AtomicInteger();

        when(diaryGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.empty());
        when(generationPromptRepository.findFirstByOrderByIdDesc()).thenReturn(Optional.of(prompt));
        when(diaryGenerationRepository.saveAndFlush(any(DiaryGeneration.class)))
                .thenAnswer(invocation -> {
                    DiaryGeneration generation = invocation.getArgument(0);
                    saveCount.incrementAndGet();
                    assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PROCESSING);
                    return generation;
                });
        when(storyboardGenerator.generate(any(StoryboardGenerationRequest.class))).thenReturn(storyboard);
        when(imageStorage.load(prompt.getImageAssetObjectKey())).thenReturn(referenceImage);
        when(diaryImageGenerator.generate(any(DiaryImageGenerationRequest.class))).thenReturn(generatedImage);
        when(imageStorage.store(any(UUID.class), eq(generatedImage))).thenReturn("generated/diary-image.png");
        when(diaryGenerationRepository.succeedProcessingGeneration(
                any(UUID.class),
                eq(storyboard),
                eq(storyboard.title()),
                eq("generated/diary-image.png"),
                eq(NOW),
                eq(GenerationStatus.PROCESSING),
                eq(GenerationStatus.SUCCEEDED)
        )).thenReturn(1);

        DiaryGenerationResult result = generateDiaryImageService.generate(command);

        assertThat(result.status()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(result.title()).isEqualTo(storyboard.title());
        assertThat(result.imageObjectKey()).isEqualTo("generated/diary-image.png");
        assertThat(result.completedAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(result.newlyCreated()).isTrue();
        assertThat(saveCount).hasValue(1);

        InOrder inOrder = inOrder(
                generationPromptRepository,
                diaryGenerationRepository,
                storyboardGenerator,
                imageStorage,
                diaryImageGenerator
        );
        inOrder.verify(diaryGenerationRepository).findByIdempotencyKey(command.idempotencyKey());
        inOrder.verify(generationPromptRepository).findFirstByOrderByIdDesc();
        inOrder.verify(diaryGenerationRepository).saveAndFlush(any(DiaryGeneration.class));
        inOrder.verify(storyboardGenerator).generate(new StoryboardGenerationRequest(
                command.diaryText(),
                prompt.getStoryboardPromptText()
        ));
        inOrder.verify(imageStorage).load(prompt.getImageAssetObjectKey());
        inOrder.verify(diaryImageGenerator).generate(new DiaryImageGenerationRequest(
                storyboard,
                prompt.getImageStylePromptText(),
                referenceImage
        ));
        inOrder.verify(imageStorage).store(result.generationId(), generatedImage);
        inOrder.verify(diaryGenerationRepository).succeedProcessingGeneration(
                result.generationId(),
                storyboard,
                storyboard.title(),
                "generated/diary-image.png",
                NOW,
                GenerationStatus.PROCESSING,
                GenerationStatus.SUCCEEDED
        );
    }

    @Test
    @DisplayName("동일한 요청을 다른 작업이 먼저 선점했다면 기존 처리 상태를 반환한다")
    void handleGenerationClaimedByConcurrentRequest() {
        GenerateDiaryImageCommand command = createCommand();
        GenerationPrompt prompt = createPrompt();
        DiaryGeneration concurrentGeneration = createGeneration(command);
        DataIntegrityViolationException exception = new DataIntegrityViolationException("중복 멱등성 키");

        when(diaryGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concurrentGeneration));
        when(generationPromptRepository.findFirstByOrderByIdDesc()).thenReturn(Optional.of(prompt));
        when(diaryGenerationRepository.saveAndFlush(any(DiaryGeneration.class))).thenThrow(exception);

        assertThatThrownBy(() -> generateDiaryImageService.generate(command))
                .isInstanceOf(GenerationInProgressException.class);
        verifyNoInteractions(storyboardGenerator, diaryImageGenerator, imageStorage);

        InOrder inOrder = inOrder(diaryGenerationRepository, generationPromptRepository);
        inOrder.verify(diaryGenerationRepository).findByIdempotencyKey(command.idempotencyKey());
        inOrder.verify(generationPromptRepository).findFirstByOrderByIdDesc();
        inOrder.verify(diaryGenerationRepository).saveAndFlush(any(DiaryGeneration.class));
        inOrder.verify(diaryGenerationRepository).findByIdempotencyKey(command.idempotencyKey());
    }

    @Test
    @DisplayName("멱등성 키 중복이 아닌 무결성 예외는 그대로 전달한다")
    void propagateUnrelatedIntegrityViolation() {
        GenerateDiaryImageCommand command = createCommand();
        GenerationPrompt prompt = createPrompt();
        DataIntegrityViolationException exception = new DataIntegrityViolationException("다른 제약 조건 위반");

        when(diaryGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.empty());
        when(generationPromptRepository.findFirstByOrderByIdDesc()).thenReturn(Optional.of(prompt));
        when(diaryGenerationRepository.saveAndFlush(any(DiaryGeneration.class))).thenThrow(exception);

        assertThatThrownBy(() -> generateDiaryImageService.generate(command)).isSameAs(exception);
        verifyNoInteractions(storyboardGenerator, diaryImageGenerator, imageStorage);
    }

    @ParameterizedTest
    @MethodSource("aiGenerationErrorMappings")
    @DisplayName("AI 생성에 실패하면 실패 상태와 오류 코드를 저장하고 예외를 다시 던진다")
    void failGenerationWhenAiGenerationFails(
            AiGenerationErrorType errorType,
            GenerationErrorCode expectedErrorCode
    ) {
        GenerateDiaryImageCommand command = createCommand();
        GenerationPrompt prompt = createPrompt();
        AiGenerationException exception = new AiGenerationException(errorType, "AI 생성에 실패했습니다.");
        AtomicInteger saveCount = new AtomicInteger();
        AtomicReference<DiaryGeneration> savedGeneration = new AtomicReference<>();

        when(diaryGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.empty());
        when(generationPromptRepository.findFirstByOrderByIdDesc()).thenReturn(Optional.of(prompt));
        when(diaryGenerationRepository.saveAndFlush(any(DiaryGeneration.class)))
                .thenAnswer(invocation -> {
                    DiaryGeneration generation = invocation.getArgument(0);
                    saveCount.incrementAndGet();
                    savedGeneration.set(generation);
                    return generation;
                });
        when(diaryGenerationRepository.failProcessingGeneration(
                any(UUID.class),
                eq(expectedErrorCode),
                eq(NOW),
                eq(GenerationStatus.PROCESSING),
                eq(GenerationStatus.FAILED)
        )).thenReturn(1);
        when(storyboardGenerator.generate(any(StoryboardGenerationRequest.class))).thenThrow(exception);

        assertThatThrownBy(() -> generateDiaryImageService.generate(command)).isSameAs(exception);
        assertThat(saveCount).hasValue(1);
        assertThat(savedGeneration.get().getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(savedGeneration.get().getErrorCode()).isEqualTo(expectedErrorCode);
        assertThat(savedGeneration.get().getCompletedAt()).isBeforeOrEqualTo(Instant.now());
        verifyNoInteractions(diaryImageGenerator, imageStorage);
    }

    @Test
    @DisplayName("이미지 저장에 실패하면 실패 상태와 오류 코드를 저장하고 예외를 다시 던진다")
    void failGenerationWhenImageStorageFails() {
        GenerateDiaryImageCommand command = createCommand();
        GenerationPrompt prompt = createPrompt();
        Storyboard storyboard = createStoryboard();
        ReferenceImage referenceImage = createReferenceImage();
        GeneratedImage generatedImage = createGeneratedImage();
        ImageStorageException exception = new ImageStorageException("이미지 저장에 실패했습니다.");
        AtomicInteger saveCount = new AtomicInteger();
        AtomicReference<DiaryGeneration> savedGeneration = new AtomicReference<>();

        when(diaryGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.empty());
        when(generationPromptRepository.findFirstByOrderByIdDesc()).thenReturn(Optional.of(prompt));
        when(diaryGenerationRepository.saveAndFlush(any(DiaryGeneration.class)))
                .thenAnswer(invocation -> {
                    DiaryGeneration generation = invocation.getArgument(0);
                    saveCount.incrementAndGet();
                    savedGeneration.set(generation);
                    return generation;
                });
        when(diaryGenerationRepository.failProcessingGeneration(
                any(UUID.class),
                eq(GenerationErrorCode.IMAGE_STORAGE_ERROR),
                eq(NOW),
                eq(GenerationStatus.PROCESSING),
                eq(GenerationStatus.FAILED)
        )).thenReturn(1);
        when(storyboardGenerator.generate(any(StoryboardGenerationRequest.class))).thenReturn(storyboard);
        when(imageStorage.load(prompt.getImageAssetObjectKey())).thenReturn(referenceImage);
        when(diaryImageGenerator.generate(any(DiaryImageGenerationRequest.class))).thenReturn(generatedImage);
        when(imageStorage.store(any(UUID.class), eq(generatedImage))).thenThrow(exception);

        assertThatThrownBy(() -> generateDiaryImageService.generate(command)).isSameAs(exception);
        assertThat(saveCount).hasValue(1);
        assertThat(savedGeneration.get().getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(savedGeneration.get().getErrorCode()).isEqualTo(GenerationErrorCode.IMAGE_STORAGE_ERROR);
        assertThat(savedGeneration.get().getCompletedAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("예상하지 못한 런타임 예외가 발생하면 중단 오류로 실패 상태를 저장하고 예외를 다시 던진다")
    void failGenerationWhenUnexpectedRuntimeExceptionOccurs() {
        GenerateDiaryImageCommand command = createCommand();
        GenerationPrompt prompt = createPrompt();
        IllegalStateException exception = new IllegalStateException("예상하지 못한 오류");
        AtomicInteger saveCount = new AtomicInteger();
        AtomicReference<DiaryGeneration> savedGeneration = new AtomicReference<>();

        when(diaryGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.empty());
        when(generationPromptRepository.findFirstByOrderByIdDesc()).thenReturn(Optional.of(prompt));
        when(diaryGenerationRepository.saveAndFlush(any(DiaryGeneration.class)))
                .thenAnswer(invocation -> {
                    DiaryGeneration generation = invocation.getArgument(0);
                    saveCount.incrementAndGet();
                    savedGeneration.set(generation);
                    return generation;
                });
        when(diaryGenerationRepository.failProcessingGeneration(
                any(UUID.class),
                eq(GenerationErrorCode.GENERATION_INTERRUPTED),
                eq(NOW),
                eq(GenerationStatus.PROCESSING),
                eq(GenerationStatus.FAILED)
        )).thenReturn(1);
        when(storyboardGenerator.generate(any(StoryboardGenerationRequest.class))).thenThrow(exception);

        assertThatThrownBy(() -> generateDiaryImageService.generate(command)).isSameAs(exception);
        assertThat(saveCount).hasValue(1);
        assertThat(savedGeneration.get().getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(savedGeneration.get().getErrorCode()).isEqualTo(GenerationErrorCode.GENERATION_INTERRUPTED);
        assertThat(savedGeneration.get().getCompletedAt()).isBeforeOrEqualTo(Instant.now());
        verifyNoInteractions(diaryImageGenerator, imageStorage);
    }

    @Test
    @DisplayName("성공 상태 저장 중 런타임 예외가 발생하면 실패 전이 없이 원래 예외를 던진다")
    void preserveExceptionWhenSavingSuccessfulGenerationFails() {
        GenerateDiaryImageCommand command = createCommand();
        GenerationPrompt prompt = createPrompt();
        Storyboard storyboard = createStoryboard();
        ReferenceImage referenceImage = createReferenceImage();
        GeneratedImage generatedImage = createGeneratedImage();
        IllegalStateException exception = new IllegalStateException("성공 상태 저장 실패");
        AtomicInteger saveCount = new AtomicInteger();

        when(diaryGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.empty());
        when(generationPromptRepository.findFirstByOrderByIdDesc()).thenReturn(Optional.of(prompt));
        when(diaryGenerationRepository.saveAndFlush(any(DiaryGeneration.class)))
                .thenAnswer(invocation -> {
                    saveCount.incrementAndGet();
                    return invocation.getArgument(0);
                });
        when(storyboardGenerator.generate(any(StoryboardGenerationRequest.class))).thenReturn(storyboard);
        when(imageStorage.load(prompt.getImageAssetObjectKey())).thenReturn(referenceImage);
        when(diaryImageGenerator.generate(any(DiaryImageGenerationRequest.class))).thenReturn(generatedImage);
        when(imageStorage.store(any(UUID.class), eq(generatedImage))).thenReturn("generated/diary-image.png");
        when(diaryGenerationRepository.succeedProcessingGeneration(
                any(UUID.class),
                eq(storyboard),
                eq(storyboard.title()),
                eq("generated/diary-image.png"),
                eq(NOW),
                eq(GenerationStatus.PROCESSING),
                eq(GenerationStatus.SUCCEEDED)
        )).thenThrow(exception);

        assertThatThrownBy(() -> generateDiaryImageService.generate(command)).isSameAs(exception);
        assertThat(saveCount).hasValue(1);
    }

    @Test
    @DisplayName("동일한 요청이 이미 성공했다면 저장된 결과를 반환한다")
    void returnExistingSuccessfulGeneration() {
        GenerateDiaryImageCommand command = createCommand();
        Storyboard storyboard = createStoryboard();
        Instant completedAt = Instant.parse("2026-08-10T10:00:00Z");
        DiaryGeneration generation = createGeneration(command);
        generation.succeed(storyboard, "generated/existing.png", completedAt);
        when(diaryGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.of(generation));

        DiaryGenerationResult result = generateDiaryImageService.generate(command);

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
        GenerateDiaryImageCommand command = createCommand();
        String requestFingerprint = requestFingerprintGenerator.generate(command);
        String differentFingerprint = createDifferentFingerprint(requestFingerprint);
        DiaryGeneration generation = DiaryGeneration.start(
                command.diaryId(),
                1L,
                command.idempotencyKey(),
                differentFingerprint
        );
        when(diaryGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.of(generation));

        assertThatThrownBy(() -> generateDiaryImageService.generate(command))
                .isInstanceOf(IdempotencyKeyConflictException.class)
                .hasMessageContaining("멱등성 키");
        verifyOnlyIdempotencyLookup(command);
    }

    @Test
    @DisplayName("동일한 요청이 처리 중이라면 예외가 발생한다")
    void rejectProcessingGeneration() {
        GenerateDiaryImageCommand command = createCommand();
        DiaryGeneration generation = createGeneration(command);
        when(diaryGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.of(generation));

        assertThatThrownBy(() -> generateDiaryImageService.generate(command))
                .isInstanceOf(GenerationInProgressException.class)
                .hasMessageContaining("처리 중");
        verifyOnlyIdempotencyLookup(command);
    }

    @Test
    @DisplayName("동일한 요청의 처리 상태가 만료되었다면 중단 오류로 실패 처리한다")
    void failExpiredProcessingGeneration() {
        GenerateDiaryImageCommand command = createCommand();
        DiaryGeneration generation = createGeneration(command);
        Instant expiredUpdatedAt = NOW.minus(PROCESSING_TIMEOUT).minusNanos(1);
        ReflectionTestUtils.setField(generation, "updatedAt", expiredUpdatedAt);
        when(diaryGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.of(generation));
        when(diaryGenerationRepository.expireProcessingGeneration(
                generation.getId(),
                NOW.minus(PROCESSING_TIMEOUT),
                NOW
        )).thenReturn(1);

        assertThatThrownBy(() -> generateDiaryImageService.generate(command))
                .isInstanceOfSatisfying(
                        DiaryGenerationFailedException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(GenerationErrorCode.GENERATION_INTERRUPTED)
                );

        verify(diaryGenerationRepository).expireProcessingGeneration(
                generation.getId(),
                NOW.minus(PROCESSING_TIMEOUT),
                NOW
        );
        verifyNoInteractions(
                generationPromptRepository,
                storyboardGenerator,
                diaryImageGenerator,
                imageStorage
        );
    }

    @Test
    @DisplayName("만료 처리 중 다른 작업이 먼저 성공했다면 저장된 성공 결과를 반환한다")
    void returnGenerationCompletedDuringExpiration() {
        GenerateDiaryImageCommand command = createCommand();
        String requestFingerprint = requestFingerprintGenerator.generate(command);
        UUID generationId = UUID.randomUUID();
        DiaryGeneration staleGeneration = mock(DiaryGeneration.class);
        DiaryGeneration completedGeneration = mock(DiaryGeneration.class);

        when(staleGeneration.getId()).thenReturn(generationId);
        when(staleGeneration.getRequestFingerprint()).thenReturn(requestFingerprint);
        when(staleGeneration.getStatus()).thenReturn(GenerationStatus.PROCESSING);
        when(staleGeneration.getUpdatedAt())
                .thenReturn(NOW.minus(PROCESSING_TIMEOUT).minusNanos(1));
        when(completedGeneration.getId()).thenReturn(generationId);
        when(completedGeneration.getStatus()).thenReturn(GenerationStatus.SUCCEEDED);
        when(completedGeneration.getTitle()).thenReturn("완료된 그림일기");
        when(completedGeneration.getImageObjectKey()).thenReturn("generated/completed.png");
        when(completedGeneration.getCompletedAt()).thenReturn(NOW.minusSeconds(1));
        when(diaryGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.of(staleGeneration));
        when(diaryGenerationRepository.expireProcessingGeneration(
                generationId,
                NOW.minus(PROCESSING_TIMEOUT),
                NOW
        )).thenReturn(0);
        when(diaryGenerationRepository.findById(generationId))
                .thenReturn(Optional.of(completedGeneration));

        DiaryGenerationResult result = generateDiaryImageService.generate(command);

        assertThat(result.generationId()).isEqualTo(generationId);
        assertThat(result.status()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(result.newlyCreated()).isFalse();
        verifyNoInteractions(
                generationPromptRepository,
                storyboardGenerator,
                diaryImageGenerator,
                imageStorage
        );
    }

    @Test
    @DisplayName("처리 제한 시간의 경계에 있는 요청은 계속 처리 중으로 판단한다")
    void keepProcessingGenerationAtExpirationBoundary() {
        GenerateDiaryImageCommand command = createCommand();
        DiaryGeneration generation = createGeneration(command);
        ReflectionTestUtils.setField(generation, "updatedAt", NOW.minus(PROCESSING_TIMEOUT));
        when(diaryGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.of(generation));

        assertThatThrownBy(() -> generateDiaryImageService.generate(command))
                .isInstanceOf(GenerationInProgressException.class);

        verifyOnlyIdempotencyLookup(command);
    }

    @Test
    @DisplayName("동일한 요청이 이미 실패했다면 저장된 오류 코드와 함께 예외가 발생한다")
    void rejectFailedGeneration() {
        GenerateDiaryImageCommand command = createCommand();
        DiaryGeneration generation = createGeneration(command);
        generation.fail(GenerationErrorCode.AI_PROVIDER_TIMEOUT, Instant.now());
        when(diaryGenerationRepository.findByIdempotencyKey(command.idempotencyKey()))
                .thenReturn(Optional.of(generation));

        assertThatThrownBy(() -> generateDiaryImageService.generate(command))
                .isInstanceOfSatisfying(
                        DiaryGenerationFailedException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(GenerationErrorCode.AI_PROVIDER_TIMEOUT)
                );
        verifyOnlyIdempotencyLookup(command);
    }

    private GenerateDiaryImageCommand createCommand() {
        return new GenerateDiaryImageCommand(
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

    private DiaryGeneration createGeneration(GenerateDiaryImageCommand command) {
        return DiaryGeneration.start(
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

    private void verifyOnlyIdempotencyLookup(GenerateDiaryImageCommand command) {
        verify(diaryGenerationRepository).findByIdempotencyKey(command.idempotencyKey());
        verifyNoMoreInteractions(diaryGenerationRepository);
        verifyNoInteractions(
                generationPromptRepository,
                storyboardGenerator,
                diaryImageGenerator,
                imageStorage
        );
    }
}
