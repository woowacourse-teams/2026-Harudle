package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.exception.DiaryNotFoundException;
import com.harudle.generation.config.GenerationLifecycleProperties;
import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.prompt.domain.GenerationPrompt;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import com.harudle.generation.diary.service.RequestFingerprintGenerator;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import com.harudle.generation.diary.service.exception.GenerationUnavailableException;
import com.harudle.generation.diary.service.exception.IdempotencyKeyConflictException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
class DiaryCreationClaimServiceTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("7e5cc251-fdde-4cc0-a54e-2c8142750609");
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 6);
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final int REQUEST_FINGERPRINT_HEX_LENGTH = 64;

    @Mock
    private DiaryRepository diaryRepository;

    @Mock
    private GenerationPromptRepository generationPromptRepository;

    @Mock
    private DiaryGenerationRepository diaryGenerationRepository;

    @Mock
    private RequestFingerprintGenerator requestFingerprintGenerator;

    private DiaryCreationClaimService claimService;

    @BeforeEach
    void setUp() {
        lenient().when(requestFingerprintGenerator.generate(any(GenerateDiaryImageCommand.class)))
                .thenAnswer(invocation -> fingerprintFor(invocation.getArgument(0)));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        claimService = new DiaryCreationClaimService(
                diaryRepository,
                generationPromptRepository,
                diaryGenerationRepository,
                requestFingerprintGenerator,
                clock,
                new GenerationLifecycleProperties(
                        Duration.ofMinutes(15),
                        Duration.ofMinutes(1)
                )
        );
    }

    @Test
    @DisplayName("일기와 처리 중 생성 기록을 저장해 신규 생성을 선점한다")
    void claimNewDiaryCreation() {
        CreateDiaryCommand command = createCommand("오늘 친구와 카페에 갔다.");
        GenerationPrompt prompt = createPrompt();
        when(diaryGenerationRepository.findByIdempotencyKeyForUpdate(IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(generationPromptRepository.findFirstByOrderByIdDesc()).thenReturn(Optional.of(prompt));
        when(diaryRepository.save(any(Diary.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryGenerationRepository.saveAndFlush(any(DiaryGeneration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DiaryCreationClaim claim = claimService.claim(command, true);

        assertThat(claim.newlyCreated()).isTrue();
        assertThat(claim.sourceText()).isEqualTo(command.sourceText());
        assertThat(claim.generationStatus()).isEqualTo(GenerationStatus.PROCESSING);
    }

    @Test
    @DisplayName("성공한 멱등 요청은 일기와 생성 기록을 새로 만들지 않는다")
    void claimExistingSuccessfulCreation() {
        CreateDiaryCommand command = createCommand("오늘 친구와 카페에 갔다.");
        Diary diary = Diary.create(USER_ID, DIARY_DATE, command.sourceText());
        DiaryGeneration generation = createGeneration(command, diary);
        generation.succeed(
                TestStoryboardFactory.create(),
                "generated/comic.png",
                Instant.parse("2026-08-06T12:00:00Z")
        );
        when(diaryGenerationRepository.findByIdempotencyKeyForUpdate(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(generation));
        when(diaryRepository.findByIdIncludingDeletedForUpdate(diary.getId()))
                .thenReturn(Optional.of(diary));

        DiaryCreationClaim claim = claimService.claim(command, false);

        assertThat(claim.newlyCreated()).isFalse();
        assertThat(claim.diaryId()).isEqualTo(diary.getId());
        assertThat(claim.title()).isEqualTo("친구와 보낸 하루");
        verify(diaryRepository, never()).save(any(Diary.class));
    }

    @Test
    @DisplayName("같은 멱등성 키를 다른 요청에 사용하면 충돌한다")
    void claimRejectsDifferentRequestForSameIdempotencyKey() {
        CreateDiaryCommand originalCommand = createCommand("원래 일기");
        CreateDiaryCommand differentCommand = createCommand("다른 일기");
        Diary diary = Diary.create(USER_ID, DIARY_DATE, originalCommand.sourceText());
        DiaryGeneration generation = createGeneration(originalCommand, diary);
        when(diaryGenerationRepository.findByIdempotencyKeyForUpdate(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(generation));
        when(diaryRepository.findByIdIncludingDeletedForUpdate(diary.getId()))
                .thenReturn(Optional.of(diary));

        assertThatThrownBy(() -> claimService.claim(differentCommand, true))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    @DisplayName("삭제된 일기의 멱등 재요청은 기존 내용을 다시 노출하지 않는다")
    void rejectIdempotentReplayForDeletedDiary() {
        CreateDiaryCommand command = createCommand("오늘 친구와 카페에 갔다.");
        Diary diary = Diary.create(USER_ID, DIARY_DATE, command.sourceText());
        DiaryGeneration generation = createGeneration(command, diary);
        generation.succeed(TestStoryboardFactory.create(), "generated/comic.png", NOW.minusSeconds(2));
        diary.delete(NOW.minusSeconds(1));
        when(diaryGenerationRepository.findByIdempotencyKeyForUpdate(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(generation));
        when(diaryRepository.findByIdIncludingDeletedForUpdate(diary.getId()))
                .thenReturn(Optional.of(diary));

        assertThatThrownBy(() -> claimService.claim(command, true))
                .isInstanceOf(DiaryNotFoundException.class);

        verify(diaryRepository, never()).save(any(Diary.class));
    }

    @Test
    @DisplayName("실패로 폐기된 일기라도 같은 멱등성 키의 다른 요청은 충돌한다")
    void rejectDifferentRequestForFailedDiscardedDiary() {
        CreateDiaryCommand originalCommand = createCommand("원래 일기");
        CreateDiaryCommand differentCommand = createCommand("다른 일기");
        Diary diary = Diary.create(USER_ID, DIARY_DATE, originalCommand.sourceText());
        DiaryGeneration generation = createGeneration(originalCommand, diary);
        generation.fail(GenerationErrorCode.AI_PROVIDER_TIMEOUT, NOW.minusSeconds(1));
        diary.delete(NOW.minusSeconds(1));
        when(diaryGenerationRepository.findByIdempotencyKeyForUpdate(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(generation));
        when(diaryRepository.findByIdIncludingDeletedForUpdate(diary.getId()))
                .thenReturn(Optional.of(diary));

        assertThatThrownBy(() -> claimService.claim(differentCommand, true))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    @DisplayName("실패로 폐기된 일기의 멱등 재요청은 저장된 실패를 복원한다")
    void claimExistingFailedDiscardedDiary() {
        CreateDiaryCommand command = createCommand("오늘 친구와 카페에 갔다.");
        Diary diary = Diary.create(USER_ID, DIARY_DATE, command.sourceText());
        DiaryGeneration generation = createGeneration(command, diary);
        Instant failedAt = NOW.minusSeconds(1);
        generation.fail(GenerationErrorCode.AI_PROVIDER_TIMEOUT, failedAt);
        diary.delete(failedAt);
        when(diaryGenerationRepository.findByIdempotencyKeyForUpdate(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(generation));
        when(diaryRepository.findByIdIncludingDeletedForUpdate(diary.getId()))
                .thenReturn(Optional.of(diary));

        DiaryCreationClaim claim = claimService.claim(command, true);

        assertThat(claim.generationStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(claim.errorCode()).isEqualTo(GenerationErrorCode.AI_PROVIDER_TIMEOUT);
        assertThat(claim.newlyCreated()).isFalse();
    }

    @Test
    @DisplayName("처리 시간이 지난 생성은 중단하고 일기를 함께 폐기한다")
    void interruptStaleGenerationAndDiscardDiary() {
        CreateDiaryCommand command = createCommand("오늘 친구와 카페에 갔다.");
        Diary diary = Diary.create(USER_ID, DIARY_DATE, command.sourceText());
        DiaryGeneration generation = createGeneration(command, diary);
        ReflectionTestUtils.setField(
                generation,
                "updatedAt",
                NOW.minus(Duration.ofMinutes(16))
        );
        when(diaryGenerationRepository.findByIdempotencyKeyForUpdate(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(generation));
        when(diaryRepository.findByIdIncludingDeletedForUpdate(diary.getId()))
                .thenReturn(Optional.of(diary));

        DiaryCreationClaim claim = claimService.claim(command, true);

        assertThat(claim.generationStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(claim.errorCode()).isEqualTo(GenerationErrorCode.GENERATION_INTERRUPTED);
        assertThat(diary.isDeleted()).isTrue();
        assertThat(generation.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("동시 선점 복구 시 기존 멱등 요청이 없으면 빈 결과를 반환한다")
    void findNoConcurrentClaim() {
        CreateDiaryCommand command = createCommand("오늘 친구와 카페에 갔다.");
        when(diaryGenerationRepository.findByIdempotencyKeyForUpdate(IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());

        Optional<DiaryCreationClaim> claim = claimService.findExistingClaim(command);

        assertThat(claim).isEmpty();
        verify(diaryRepository, never()).save(any(Diary.class));
    }

    @Test
    @DisplayName("신규 요청은 AI 생성 어댑터가 없으면 선점하지 않는다")
    void claimRejectsNewCreationWithoutGenerationAdapter() {
        CreateDiaryCommand command = createCommand("오늘의 일기");
        when(diaryGenerationRepository.findByIdempotencyKeyForUpdate(IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> claimService.claim(command, false))
                .isInstanceOf(GenerationUnavailableException.class);
        verify(diaryRepository, never()).save(any(Diary.class));
    }

    private CreateDiaryCommand createCommand(String sourceText) {
        return new CreateDiaryCommand(USER_ID, DIARY_DATE, sourceText, IDEMPOTENCY_KEY);
    }

    private DiaryGeneration createGeneration(CreateDiaryCommand command, Diary diary) {
        GenerateDiaryImageCommand generationCommand = new GenerateDiaryImageCommand(
                command.userId(),
                diary.getId(),
                command.diaryDate(),
                command.sourceText(),
                command.idempotencyKey()
        );
        return DiaryGeneration.start(
                diary.getId(),
                1L,
                command.idempotencyKey(),
                requestFingerprintGenerator.generate(generationCommand)
        );
    }

    private GenerationPrompt createPrompt() {
        GenerationPrompt prompt = org.mockito.Mockito.mock(GenerationPrompt.class);
        when(prompt.getId()).thenReturn(1L);
        return prompt;
    }

    private String fingerprintFor(GenerateDiaryImageCommand command) {
        if (command.diaryText().equals("다른 일기")) {
            return "b".repeat(REQUEST_FINGERPRINT_HEX_LENGTH);
        }
        return "a".repeat(REQUEST_FINGERPRINT_HEX_LENGTH);
    }
}
