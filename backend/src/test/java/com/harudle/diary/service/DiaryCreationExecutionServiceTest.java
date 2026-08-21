package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.service.DiaryGenerationExecutor;
import com.harudle.generation.service.dto.CompletedDiaryGeneration;
import com.harudle.generation.service.dto.GenerateDiaryImageCommand;
import com.harudle.generation.service.exception.DiaryGenerationFailedException;
import com.harudle.generation.service.exception.GenerationInProgressException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DiaryCreationExecutionServiceTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID DIARY_ID = UUID.fromString("6b66acba-0136-4822-8a59-f355dd7c977d");
    private static final UUID GENERATION_ID = UUID.fromString("17ac16ef-c45a-40bb-92ea-aed37659ef1c");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("7e5cc251-fdde-4cc0-a54e-2c8142750609");
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 6);
    private static final Instant CREATED_AT = Instant.parse("2026-08-06T11:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-06T12:00:00Z");

    @Mock
    private DiaryGenerationExecutor generationExecutor;

    private DiaryCreationExecutionService executionService;

    @BeforeEach
    void setUp() {
        executionService = new DiaryCreationExecutionService(generationExecutor);
    }

    @Test
    @DisplayName("생성 어댑터 구성 여부를 그대로 반환한다")
    void exposeGenerationAvailability() {
        when(generationExecutor.isConfigured()).thenReturn(true);

        assertThat(executionService.isGenerationAvailable()).isTrue();
    }

    @Test
    @DisplayName("신규 생성 선점은 외부 생성을 한 번 실행한다")
    void executeNewClaim() {
        CreateDiaryCommand command = createCommand();
        DiaryCreationClaim claim = createClaim(GenerationStatus.PROCESSING, true);
        when(generationExecutor.generate(any(GenerateDiaryImageCommand.class), eq(GENERATION_ID)))
                .thenReturn(createCompletedGeneration());

        DiaryCreationExecution result = executionService.execute(command, claim);

        assertThat(result.newlyCreated()).isTrue();
        assertThat(result.generation().status()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(result.generation().title()).isEqualTo("친구와 보낸 하루");
        verify(generationExecutor).generate(any(GenerateDiaryImageCommand.class), eq(GENERATION_ID));
    }

    @Test
    @DisplayName("성공한 기존 선점은 외부 생성을 다시 실행하지 않는다")
    void returnExistingSuccessfulClaim() {
        DiaryCreationClaim claim = createClaim(GenerationStatus.SUCCEEDED, false);

        DiaryCreationExecution result = executionService.execute(createCommand(), claim);

        assertThat(result.newlyCreated()).isFalse();
        assertThat(result.generation().title()).isEqualTo("친구와 보낸 하루");
        verify(generationExecutor, never()).generate(any(), any());
    }

    @Test
    @DisplayName("처리 중인 기존 선점은 외부 생성을 다시 실행하지 않는다")
    void rejectExistingProcessingClaim() {
        DiaryCreationClaim claim = createClaim(GenerationStatus.PROCESSING, false);

        assertThatThrownBy(() -> executionService.execute(createCommand(), claim))
                .isInstanceOf(GenerationInProgressException.class);

        verify(generationExecutor, never()).generate(any(), any());
    }

    @Test
    @DisplayName("실패한 기존 선점은 저장된 생성 오류를 반환한다")
    void rejectExistingFailedClaim() {
        DiaryCreationClaim claim = createClaim(GenerationStatus.FAILED, false);

        assertThatThrownBy(() -> executionService.execute(createCommand(), claim))
                .isInstanceOfSatisfying(
                        DiaryGenerationFailedException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(GenerationErrorCode.AI_PROVIDER_TIMEOUT)
                );

        verify(generationExecutor, never()).generate(any(), any());
    }

    private CreateDiaryCommand createCommand() {
        return new CreateDiaryCommand(
                USER_ID,
                DIARY_DATE,
                "오늘 친구와 카페에 갔다.",
                IDEMPOTENCY_KEY
        );
    }

    private DiaryCreationClaim createClaim(
            GenerationStatus status,
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
        return new DiaryCreationClaim(
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
    }

    private CompletedDiaryGeneration createCompletedGeneration() {
        return new CompletedDiaryGeneration(
                GENERATION_ID,
                "친구와 보낸 하루",
                "generated/comic.png",
                COMPLETED_AT
        );
    }
}
