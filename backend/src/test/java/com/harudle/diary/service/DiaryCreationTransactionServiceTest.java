package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.exception.DiaryNotFoundException;
import com.harudle.generation.domain.ComicGeneration;
import com.harudle.generation.domain.GenerationPrompt;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.repository.ComicGenerationRepository;
import com.harudle.generation.repository.GenerationPromptRepository;
import com.harudle.generation.service.GenerationUsageService;
import com.harudle.generation.service.RequestFingerprintGenerator;
import com.harudle.generation.service.dto.GenerateComicCommand;
import com.harudle.generation.service.exception.GenerationUnavailableException;
import com.harudle.generation.service.exception.IdempotencyKeyConflictException;
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

@ExtendWith(MockitoExtension.class)
class DiaryCreationTransactionServiceTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("7e5cc251-fdde-4cc0-a54e-2c8142750609");
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 6);

    @Mock
    private DiaryRepository diaryRepository;

    @Mock
    private GenerationPromptRepository generationPromptRepository;

    @Mock
    private ComicGenerationRepository comicGenerationRepository;

    @Mock
    private GenerationUsageService generationUsageService;

    private RequestFingerprintGenerator requestFingerprintGenerator;
    private DiaryCreationTransactionService transactionService;

    @BeforeEach
    void setUp() {
        requestFingerprintGenerator = new RequestFingerprintGenerator();
        Clock clock = Clock.fixed(Instant.parse("2026-08-06T12:00:00Z"), ZoneOffset.UTC);
        transactionService = new DiaryCreationTransactionService(
                diaryRepository,
                generationPromptRepository,
                comicGenerationRepository,
                generationUsageService,
                requestFingerprintGenerator,
                clock,
                Duration.ofMinutes(15)
        );
    }

    @Test
    @DisplayName("일기와 처리 중 생성 기록을 저장하고 오늘 사용량을 증가시켜 선점한다")
    void claimNewDiaryCreation() {
        CreateDiaryCommand command = createCommand("오늘 친구와 카페에 갔다.");
        GenerationPrompt prompt = createPrompt();
        GenerationUsage usage = new GenerationUsage(DIARY_DATE, 1, 3);
        when(comicGenerationRepository.findByIdempotencyKeyForUpdate(IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(generationPromptRepository.findFirstByOrderByIdDesc()).thenReturn(Optional.of(prompt));
        when(diaryRepository.save(any(Diary.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(comicGenerationRepository.saveAndFlush(any(ComicGeneration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(generationUsageService.incrementTodayUsage(USER_ID)).thenReturn(usage);

        DiaryCreationClaim claim = transactionService.claim(command, true);

        assertThat(claim.newlyCreated()).isTrue();
        assertThat(claim.sourceText()).isEqualTo(command.sourceText());
        assertThat(claim.generationStatus()).isEqualTo(GenerationStatus.PROCESSING);
        assertThat(claim.usage()).isEqualTo(usage);
    }

    @Test
    @DisplayName("성공한 멱등 요청은 일기와 사용량을 새로 만들지 않는다")
    void claimExistingSuccessfulCreation() {
        CreateDiaryCommand command = createCommand("오늘 친구와 카페에 갔다.");
        Diary diary = Diary.create(USER_ID, DIARY_DATE, command.sourceText());
        ComicGeneration generation = createGeneration(command, diary);
        generation.succeed(
                TestStoryboardFactory.create(),
                "generated/comic.png",
                Instant.parse("2026-08-06T12:00:00Z")
        );
        GenerationUsage usage = new GenerationUsage(DIARY_DATE, 1, 3);
        when(comicGenerationRepository.findByIdempotencyKeyForUpdate(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(generation));
        when(diaryRepository.findActiveById(diary.getId())).thenReturn(Optional.of(diary));
        when(generationUsageService.getTodayUsage(USER_ID)).thenReturn(usage);

        DiaryCreationClaim claim = transactionService.claim(command, false);

        assertThat(claim.newlyCreated()).isFalse();
        assertThat(claim.diaryId()).isEqualTo(diary.getId());
        assertThat(claim.title()).isEqualTo("친구와 보낸 하루");
        assertThat(claim.usage()).isEqualTo(usage);
        verify(generationUsageService, never()).incrementTodayUsage(USER_ID);
    }

    @Test
    @DisplayName("같은 멱등성 키를 다른 요청에 사용하면 충돌한다")
    void claimRejectsDifferentRequestForSameIdempotencyKey() {
        CreateDiaryCommand originalCommand = createCommand("원래 일기");
        CreateDiaryCommand differentCommand = createCommand("다른 일기");
        Diary diary = Diary.create(USER_ID, DIARY_DATE, originalCommand.sourceText());
        ComicGeneration generation = createGeneration(originalCommand, diary);
        when(comicGenerationRepository.findByIdempotencyKeyForUpdate(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(generation));
        when(diaryRepository.findActiveById(diary.getId())).thenReturn(Optional.of(diary));

        assertThatThrownBy(() -> transactionService.claim(differentCommand, true))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    @DisplayName("삭제된 일기의 멱등 재요청은 기존 내용을 다시 노출하지 않는다")
    void rejectIdempotentReplayForDeletedDiary() {
        CreateDiaryCommand command = createCommand("오늘 친구와 카페에 갔다.");
        Diary diary = Diary.create(USER_ID, DIARY_DATE, command.sourceText());
        ComicGeneration generation = createGeneration(command, diary);
        when(comicGenerationRepository.findByIdempotencyKeyForUpdate(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(generation));
        when(diaryRepository.findActiveById(diary.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.claim(command, true))
                .isInstanceOf(DiaryNotFoundException.class);

        verify(generationUsageService, never()).getTodayUsage(USER_ID);
        verify(diaryRepository, never()).save(any(Diary.class));
    }

    @Test
    @DisplayName("동시 선점 복구 시 기존 멱등 요청이 없으면 빈 결과를 반환한다")
    void findNoConcurrentClaim() {
        CreateDiaryCommand command = createCommand("오늘 친구와 카페에 갔다.");
        when(comicGenerationRepository.findByIdempotencyKeyForUpdate(IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());

        Optional<DiaryCreationClaim> claim = transactionService.findExistingClaim(command);

        assertThat(claim).isEmpty();
        verify(diaryRepository, never()).save(any(Diary.class));
        verify(generationUsageService, never()).incrementTodayUsage(USER_ID);
    }

    @Test
    @DisplayName("신규 요청은 AI 생성 어댑터가 없으면 선점하지 않는다")
    void claimRejectsNewCreationWithoutGenerationAdapter() {
        CreateDiaryCommand command = createCommand("오늘의 일기");
        when(comicGenerationRepository.findByIdempotencyKeyForUpdate(IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.claim(command, false))
                .isInstanceOf(GenerationUnavailableException.class);
        verify(diaryRepository, never()).save(any(Diary.class));
        verify(generationUsageService, never()).incrementTodayUsage(USER_ID);
    }

    private CreateDiaryCommand createCommand(String sourceText) {
        return new CreateDiaryCommand(USER_ID, DIARY_DATE, sourceText, IDEMPOTENCY_KEY);
    }

    private ComicGeneration createGeneration(CreateDiaryCommand command, Diary diary) {
        GenerateComicCommand generationCommand = new GenerateComicCommand(
                command.userId(),
                diary.getId(),
                command.diaryDate(),
                command.sourceText(),
                command.idempotencyKey()
        );
        return ComicGeneration.start(
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
}
