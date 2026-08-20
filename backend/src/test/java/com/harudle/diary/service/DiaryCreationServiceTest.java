package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;
import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.service.DiaryGenerationExecutor;
import com.harudle.generation.service.dto.CompletedDiaryGeneration;
import com.harudle.generation.service.dto.GenerateDiaryImageCommand;
import com.harudle.generation.service.exception.DiaryGenerationFailedException;
import com.harudle.generation.service.exception.GenerationInProgressException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class DiaryCreationServiceTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID DIARY_ID = UUID.fromString("6b66acba-0136-4822-8a59-f355dd7c977d");
    private static final UUID GENERATION_ID = UUID.fromString("17ac16ef-c45a-40bb-92ea-aed37659ef1c");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("7e5cc251-fdde-4cc0-a54e-2c8142750609");
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 6);
    private static final Instant CREATED_AT = Instant.parse("2026-08-06T11:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-06T12:00:00Z");

    @Mock
    private MemberDiaryCreationTransactionService transactionService;

    @Mock
    private DiaryGenerationExecutor generationExecutor;

    private DiaryCreationService diaryCreationService;

    @BeforeEach
    void setUp() {
        diaryCreationService = new DiaryCreationService(transactionService, generationExecutor);
    }

    @Test
    @DisplayName("신규 요청을 선점한 뒤 외부 생성을 실행해 생성 결과를 반환한다")
    void createNewDiary() {
        CreateDiaryCommand command = createCommand();
        GenerationUsage usage = new GenerationUsage(DIARY_DATE, 1, 3);
        MemberDiaryCreationClaim claim = createClaim(GenerationStatus.PROCESSING, usage, true);
        CompletedDiaryGeneration generationResult = createGenerationResult();
        when(generationExecutor.isConfigured()).thenReturn(true);
        when(transactionService.claim(command, true)).thenReturn(claim);
        when(generationExecutor.generate(any(GenerateDiaryImageCommand.class), eq(GENERATION_ID)))
                .thenReturn(generationResult);

        CreateDiaryResult result = diaryCreationService.create(command);

        assertThat(result.newlyCreated()).isTrue();
        assertThat(result.id()).isEqualTo(DIARY_ID);
        assertThat(result.generation().title()).isEqualTo("친구와 보낸 하루");
        assertThat(result.usage()).isEqualTo(usage);
    }

    @Test
    @DisplayName("성공한 멱등 재요청은 AI 생성 어댑터 없이 기존 결과를 반환한다")
    void returnExistingDiaryWithoutGenerationAdapter() {
        CreateDiaryCommand command = createCommand();
        GenerationUsage usage = new GenerationUsage(DIARY_DATE, 1, 3);
        MemberDiaryCreationClaim claim = createClaim(GenerationStatus.SUCCEEDED, usage, false);
        when(generationExecutor.isConfigured()).thenReturn(false);
        when(transactionService.claim(command, false)).thenReturn(claim);

        CreateDiaryResult result = diaryCreationService.create(command);

        assertThat(result.newlyCreated()).isFalse();
        assertThat(result.generation().status()).isEqualTo(GenerationStatus.SUCCEEDED);
        verify(generationExecutor, never()).generate(any(GenerateDiaryImageCommand.class), any(UUID.class));
    }

    @Test
    @DisplayName("처리 중인 멱등 재요청은 외부 생성을 다시 호출하지 않는다")
    void rejectExistingProcessingDiary() {
        CreateDiaryCommand command = createCommand();
        GenerationUsage usage = new GenerationUsage(DIARY_DATE, 1, 3);
        MemberDiaryCreationClaim claim = createClaim(GenerationStatus.PROCESSING, usage, false);
        when(generationExecutor.isConfigured()).thenReturn(true);
        when(transactionService.claim(command, true)).thenReturn(claim);

        assertThatThrownBy(() -> diaryCreationService.create(command))
                .isInstanceOf(GenerationInProgressException.class);
        verify(generationExecutor, never()).generate(any(GenerateDiaryImageCommand.class), any(UUID.class));
    }

    @Test
    @DisplayName("멱등성 키 경합으로 선점에 실패하면 경합에서 이긴 기존 요청만 조회한다")
    void recoverConcurrentClaim() {
        CreateDiaryCommand command = createCommand();
        GenerationUsage usage = new GenerationUsage(DIARY_DATE, 1, 3);
        MemberDiaryCreationClaim claim = createClaim(GenerationStatus.PROCESSING, usage, false);
        DataIntegrityViolationException collision = new DataIntegrityViolationException("중복 멱등성 키");
        when(generationExecutor.isConfigured()).thenReturn(true);
        when(transactionService.claim(command, true)).thenThrow(collision);
        when(transactionService.findExistingClaim(command)).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> diaryCreationService.create(command))
                .isInstanceOf(GenerationInProgressException.class);

        verify(transactionService).claim(command, true);
        verify(transactionService).findExistingClaim(command);
        verify(generationExecutor, never()).generate(any(GenerateDiaryImageCommand.class), any(UUID.class));
    }

    @Test
    @DisplayName("멱등성 키 경합이 아니면 원래 무결성 예외를 그대로 전달한다")
    void propagateUnrelatedIntegrityViolation() {
        CreateDiaryCommand command = createCommand();
        DataIntegrityViolationException exception = new DataIntegrityViolationException("다른 제약 조건 위반");
        when(generationExecutor.isConfigured()).thenReturn(true);
        when(transactionService.claim(command, true)).thenThrow(exception);
        when(transactionService.findExistingClaim(command)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> diaryCreationService.create(command)).isSameAs(exception);

        verify(transactionService).claim(command, true);
        verify(transactionService).findExistingClaim(command);
    }

    @Test
    @DisplayName("실패한 멱등 재요청은 저장된 생성 오류를 반환한다")
    void rejectExistingFailedDiary() {
        CreateDiaryCommand command = createCommand();
        GenerationUsage usage = new GenerationUsage(DIARY_DATE, 1, 3);
        MemberDiaryCreationClaim claim = createClaim(GenerationStatus.FAILED, usage, false);
        when(generationExecutor.isConfigured()).thenReturn(true);
        when(transactionService.claim(command, true)).thenReturn(claim);

        assertThatThrownBy(() -> diaryCreationService.create(command))
                .isInstanceOfSatisfying(
                        DiaryGenerationFailedException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(GenerationErrorCode.AI_PROVIDER_TIMEOUT)
                );

        verify(generationExecutor, never()).generate(any(GenerateDiaryImageCommand.class), any(UUID.class));
    }

    private CreateDiaryCommand createCommand() {
        return new CreateDiaryCommand(
                USER_ID,
                DIARY_DATE,
                "오늘 친구와 카페에 갔다.",
                IDEMPOTENCY_KEY
        );
    }

    private MemberDiaryCreationClaim createClaim(
            GenerationStatus status,
            GenerationUsage usage,
            boolean newlyCreated
    ) {
        String title = null;
        String imageObjectKey = null;
        Instant completedAt = null;
        GenerationErrorCode errorCode = null;
        if (status == GenerationStatus.SUCCEEDED) {
            title = "친구와 보낸 하루";
            imageObjectKey = "generated/comic.png";
            completedAt = COMPLETED_AT;
        }
        if (status == GenerationStatus.FAILED) {
            errorCode = GenerationErrorCode.AI_PROVIDER_TIMEOUT;
        }
        DiaryCreationClaim claim = new DiaryCreationClaim(
                DIARY_ID,
                DIARY_DATE,
                "오늘 친구와 카페에 갔다.",
                CREATED_AT,
                GENERATION_ID,
                status,
                title,
                imageObjectKey,
                completedAt,
                errorCode,
                newlyCreated
        );
        return new MemberDiaryCreationClaim(claim, usage);
    }

    private CompletedDiaryGeneration createGenerationResult() {
        return new CompletedDiaryGeneration(
                GENERATION_ID,
                "친구와 보낸 하루",
                "generated/comic.png",
                COMPLETED_AT
        );
    }
}
