package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;
import com.harudle.diary.service.dto.DiaryCreationClaim;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.service.ClaimedComicGenerationService;
import com.harudle.generation.service.dto.ComicGenerationResult;
import com.harudle.generation.service.dto.GenerateComicCommand;
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
import org.springframework.beans.factory.ObjectProvider;

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
    private DiaryCreationTransactionService transactionService;

    @Mock
    private ObjectProvider<ClaimedComicGenerationService> generationServiceProvider;

    @Mock
    private ClaimedComicGenerationService generationService;

    private DiaryCreationService diaryCreationService;

    @BeforeEach
    void setUp() {
        diaryCreationService = new DiaryCreationService(transactionService, generationServiceProvider);
    }

    @Test
    @DisplayName("신규 요청을 선점한 뒤 외부 생성을 실행해 생성 결과를 반환한다")
    void createNewDiary() {
        CreateDiaryCommand command = createCommand();
        GenerationUsage usage = new GenerationUsage(DIARY_DATE, 1, 3);
        DiaryCreationClaim claim = createClaim(GenerationStatus.PROCESSING, usage, true);
        ComicGenerationResult generationResult = createGenerationResult(true);
        when(generationServiceProvider.getIfAvailable()).thenReturn(generationService);
        when(transactionService.claim(command, true)).thenReturn(claim);
        when(generationService.generate(any(GenerateComicCommand.class), eq(GENERATION_ID)))
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
        DiaryCreationClaim claim = createClaim(GenerationStatus.SUCCEEDED, usage, false);
        when(generationServiceProvider.getIfAvailable()).thenReturn(null);
        when(transactionService.claim(command, false)).thenReturn(claim);

        CreateDiaryResult result = diaryCreationService.create(command);

        assertThat(result.newlyCreated()).isFalse();
        assertThat(result.generation().status()).isEqualTo(GenerationStatus.SUCCEEDED);
        verifyNoInteractions(generationService);
    }

    @Test
    @DisplayName("처리 중인 멱등 재요청은 외부 생성을 다시 호출하지 않는다")
    void rejectExistingProcessingDiary() {
        CreateDiaryCommand command = createCommand();
        GenerationUsage usage = new GenerationUsage(DIARY_DATE, 1, 3);
        DiaryCreationClaim claim = createClaim(GenerationStatus.PROCESSING, usage, false);
        when(generationServiceProvider.getIfAvailable()).thenReturn(generationService);
        when(transactionService.claim(command, true)).thenReturn(claim);

        assertThatThrownBy(() -> diaryCreationService.create(command))
                .isInstanceOf(GenerationInProgressException.class);
        verifyNoInteractions(generationService);
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
            GenerationUsage usage,
            boolean newlyCreated
    ) {
        boolean succeeded = status == GenerationStatus.SUCCEEDED;
        return new DiaryCreationClaim(
                DIARY_ID,
                DIARY_DATE,
                "오늘 친구와 카페에 갔다.",
                CREATED_AT,
                GENERATION_ID,
                status,
                succeeded ? "친구와 보낸 하루" : null,
                succeeded ? "generated/comic.png" : null,
                succeeded ? COMPLETED_AT : null,
                null,
                usage,
                newlyCreated
        );
    }

    private ComicGenerationResult createGenerationResult(boolean newlyCreated) {
        return new ComicGenerationResult(
                GENERATION_ID,
                GenerationStatus.SUCCEEDED,
                "친구와 보낸 하루",
                "generated/comic.png",
                COMPLETED_AT,
                newlyCreated
        );
    }
}
