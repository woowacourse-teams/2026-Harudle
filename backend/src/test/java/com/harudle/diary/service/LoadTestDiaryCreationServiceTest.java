package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;
import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
import com.harudle.generation.loadtest.LoadTestDiaryGenerationExecutor;
import com.harudle.generation.loadtest.LoadTestGenerationRegistry;
import com.harudle.generation.config.GenerationWorkerProperties;
import java.time.Duration;
import com.harudle.generation.diary.service.exception.DiaryGenerationFailedException;
import com.harudle.generation.diary.service.exception.GenerationUnavailableException;
import com.harudle.generation.usage.domain.GenerationUsage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class LoadTestDiaryCreationServiceTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID DIARY_ID = UUID.fromString("6b66acba-0136-4822-8a59-f355dd7c977d");
    private static final UUID GENERATION_ID = UUID.fromString("17ac16ef-c45a-40bb-92ea-aed37659ef1c");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("7e5cc251-fdde-4cc0-a54e-2c8142750609");
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 6);
    private static final Instant CREATED_AT = Instant.parse("2026-08-06T11:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-06T12:00:00Z");
    private static final GenerationUsage USAGE = new GenerationUsage(DIARY_DATE, 1, 3);
    private static final GenerationTokenUsage TOKEN_USAGE = new GenerationTokenUsage(120, 350, 80, 550);

    @Mock
    private MemberDiaryCreationTransactionService transactionService;

    @Mock
    private LoadTestDiaryGenerationExecutor generationExecutor;

    private LoadTestGenerationRegistry generationRegistry;

    private LoadTestDiaryCreationService diaryCreationService;

    @BeforeEach
    void setUp() {
        generationRegistry = new LoadTestGenerationRegistry(generationExecutor);
        diaryCreationService = new LoadTestDiaryCreationService(transactionService, generationRegistry,
                new GenerationWorkerProperties(true, 1, Duration.ofSeconds(5)));
    }

    @AfterEach
    void doNotGenerateImageDuringRequest() {
        verify(generationExecutor, never()).generate(any(), any());
    }

    @Test
    @DisplayName("회원 생성 요청은 이미지 생성 없이 대기 작업과 사용량을 반환한다")
    void enqueueMemberDiary() {
        CreateDiaryCommand command = createCommand();
        DiaryCreationClaim claim = createClaim(GenerationStatus.PENDING, true);
        when(generationExecutor.isConfigured()).thenReturn(true);
        when(transactionService.enqueue(eq(command), eq(true), any()))
                .thenReturn(new MemberDiaryCreationClaim(claim, USAGE));

        CreateDiaryResult result = diaryCreationService.create(command);

        assertThat(result.id()).isEqualTo(DIARY_ID);
        assertThat(result.diaryDate()).isEqualTo(DIARY_DATE);
        assertThat(result.sourceText()).isEqualTo(command.sourceText());
        assertThat(result.createdAt()).isEqualTo(CREATED_AT);
        assertThat(result.newlyCreated()).isTrue();
        assertThat(result.usage()).isEqualTo(USAGE);
        assertThat(result.generation().id()).isEqualTo(GENERATION_ID);
        assertThat(result.generation().status()).isEqualTo(GenerationStatus.PENDING);
        assertThat(result.generation().title()).isNull();
        assertThat(result.generation().imageObjectKey()).isNull();
        assertThat(result.generation().completedAt()).isNull();
        assertThat(result.generation().tokenUsage()).isNull();
        verify(transactionService, never()).claim(any(), anyBoolean());
    }

    @ParameterizedTest
    @EnumSource(value = GenerationStatus.class, names = {"PENDING", "PROCESSING", "SUCCEEDED"})
    @DisplayName("회원 멱등 재요청은 어댑터 없이도 저장된 작업 상태와 결과를 반환한다")
    void returnExistingMemberDiary(GenerationStatus status) {
        CreateDiaryCommand command = createCommand();
        DiaryCreationClaim claim = createClaim(status, false);
        when(transactionService.enqueue(eq(command), eq(false), any()))
                .thenReturn(new MemberDiaryCreationClaim(claim, USAGE));

        CreateDiaryResult result = diaryCreationService.create(command);

        assertThat(result.id()).isEqualTo(DIARY_ID);
        assertThat(result.newlyCreated()).isFalse();
        assertThat(result.usage()).isEqualTo(USAGE);
        assertThat(result.generation().id()).isEqualTo(GENERATION_ID);
        assertThat(result.generation().status()).isEqualTo(status);
        assertThat(result.generation().title()).isEqualTo(claim.title());
        assertThat(result.generation().imageObjectKey()).isEqualTo(claim.imageObjectKey());
        assertThat(result.generation().completedAt()).isEqualTo(claim.completedAt());
        assertThat(result.generation().tokenUsage()).isEqualTo(claim.tokenUsage());
        verify(transactionService).enqueue(eq(command), eq(false), any());
    }

    @Test
    @DisplayName("실패한 회원 멱등 재요청은 저장된 오류 코드를 전달한다")
    void preserveFailedMemberGeneration() {
        CreateDiaryCommand command = createCommand();
        when(transactionService.enqueue(eq(command), eq(false), any()))
                .thenReturn(new MemberDiaryCreationClaim(createClaim(GenerationStatus.FAILED, false), USAGE));

        assertThatThrownBy(() -> diaryCreationService.create(command))
                .isInstanceOfSatisfying(DiaryGenerationFailedException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(GenerationErrorCode.AI_PROVIDER_TIMEOUT));
    }

    @Test
    @DisplayName("멱등성 키 경합으로 접수에 실패하면 기존 회원 대기 작업을 반환한다")
    void recoverConcurrentEnqueue() {
        CreateDiaryCommand command = createCommand();
        MemberDiaryCreationClaim recovered = new MemberDiaryCreationClaim(
                createClaim(GenerationStatus.PENDING, false), USAGE
        );
        DataIntegrityViolationException collision = new DataIntegrityViolationException("중복 멱등성 키");
        when(generationExecutor.isConfigured()).thenReturn(true);
        when(transactionService.enqueue(eq(command), eq(true), any())).thenThrow(collision);
        when(transactionService.findExistingClaim(eq(command), any())).thenReturn(Optional.of(recovered));

        CreateDiaryResult result = diaryCreationService.create(command);

        assertThat(result.newlyCreated()).isFalse();
        assertThat(result.generation().id()).isEqualTo(GENERATION_ID);
        assertThat(result.generation().status()).isEqualTo(GenerationStatus.PENDING);
        assertThat(result.usage()).isEqualTo(USAGE);
        verify(transactionService).findExistingClaim(eq(command), any());
    }

    @Test
    @DisplayName("복구할 회원 요청이 없으면 원래 무결성 예외를 전달한다")
    void propagateUnrelatedIntegrityViolation() {
        CreateDiaryCommand command = createCommand();
        DataIntegrityViolationException exception = new DataIntegrityViolationException("다른 제약 조건 위반");
        when(generationExecutor.isConfigured()).thenReturn(true);
        when(transactionService.enqueue(eq(command), eq(true), any())).thenThrow(exception);
        when(transactionService.findExistingClaim(eq(command), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> diaryCreationService.create(command)).isSameAs(exception);

        verify(transactionService).findExistingClaim(eq(command), any());
    }

    @Test
    @DisplayName("어댑터가 준비되지 않은 신규 회원 요청은 접수 실패를 전달한다")
    void rejectNewCreationWithoutGenerationAdapter() {
        CreateDiaryCommand command = createCommand();
        when(transactionService.enqueue(eq(command), eq(false), any()))
                .thenThrow(GenerationUnavailableException.adaptersNotConfigured());

        assertThatThrownBy(() -> diaryCreationService.create(command))
                .isInstanceOf(GenerationUnavailableException.class);
    }

    @Test
    @DisplayName("작업 처리기가 꺼져 있으면 신규 테스트 접수를 거절한다")
    void rejectNewLoadTestWhenWorkerDisabled() {
        diaryCreationService = new LoadTestDiaryCreationService(transactionService, generationRegistry,
                new GenerationWorkerProperties(false, 1, Duration.ofSeconds(5)));
        CreateDiaryCommand command = createCommand();
        when(transactionService.enqueue(eq(command), eq(false), any()))
                .thenThrow(GenerationUnavailableException.adaptersNotConfigured());

        assertThatThrownBy(() -> diaryCreationService.create(command))
                .isInstanceOf(GenerationUnavailableException.class);
    }

    @Test
    @DisplayName("작업 처리기를 꺼도 이미 접수한 테스트 결과는 반환한다")
    void replayLoadTestWhenWorkerDisabled() {
        diaryCreationService = new LoadTestDiaryCreationService(transactionService, generationRegistry,
                new GenerationWorkerProperties(false, 1, Duration.ofSeconds(5)));
        CreateDiaryCommand command = createCommand();
        when(transactionService.enqueue(eq(command), eq(false), any()))
                .thenReturn(new MemberDiaryCreationClaim(createClaim(GenerationStatus.PENDING, false), USAGE));

        assertThat(diaryCreationService.create(command).newlyCreated()).isFalse();
    }

    private CreateDiaryCommand createCommand() {
        return new CreateDiaryCommand(USER_ID, DIARY_DATE, "오늘 친구와 카페에 갔다.", IDEMPOTENCY_KEY);
    }

    private DiaryCreationClaim createClaim(GenerationStatus status, boolean newlyCreated) {
        boolean succeeded = status == GenerationStatus.SUCCEEDED;
        return new DiaryCreationClaim(
                DIARY_ID, DIARY_DATE, "오늘 친구와 카페에 갔다.", CREATED_AT, GENERATION_ID, status,
                succeeded ? "친구와 보낸 하루" : null,
                succeeded ? "generated/comic.png" : null,
                succeeded || status == GenerationStatus.FAILED ? COMPLETED_AT : null,
                status == GenerationStatus.FAILED ? GenerationErrorCode.AI_PROVIDER_TIMEOUT : null,
                newlyCreated,
                succeeded ? TOKEN_USAGE : null
        );
    }
}
