package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;
import com.harudle.diary.service.dto.DiaryGenerationResult;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.domain.GenerationUsage;
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
    private DiaryCreationExecutionService executionService;

    private DiaryCreationService diaryCreationService;

    @BeforeEach
    void setUp() {
        diaryCreationService = new DiaryCreationService(transactionService, executionService);
    }

    @Test
    @DisplayName("회원 생성 선점과 공통 실행 결과에 회원 사용량을 결합한다")
    void createMemberDiary() {
        CreateDiaryCommand command = createCommand();
        GenerationUsage usage = new GenerationUsage(DIARY_DATE, 1, 3);
        DiaryCreationClaim claim = createClaim(true);
        MemberDiaryCreationClaim memberClaim = new MemberDiaryCreationClaim(claim, usage);
        DiaryCreationExecution execution = createExecution(true);
        when(executionService.isGenerationAvailable()).thenReturn(true);
        when(transactionService.claim(command, true)).thenReturn(memberClaim);
        when(executionService.execute(command, claim)).thenReturn(execution);

        CreateDiaryResult result = diaryCreationService.create(command);

        assertThat(result.id()).isEqualTo(DIARY_ID);
        assertThat(result.usage()).isEqualTo(usage);
        assertThat(result.newlyCreated()).isTrue();
        assertThat(result.generation().title()).isEqualTo("친구와 보낸 하루");
    }

    @Test
    @DisplayName("성공한 회원 멱등 재요청은 기존 생성 결과와 사용량을 반환한다")
    void returnExistingMemberDiary() {
        CreateDiaryCommand command = createCommand();
        GenerationUsage usage = new GenerationUsage(DIARY_DATE, 1, 3);
        DiaryCreationClaim claim = createClaim(false);
        MemberDiaryCreationClaim memberClaim = new MemberDiaryCreationClaim(claim, usage);
        when(executionService.isGenerationAvailable()).thenReturn(false);
        when(transactionService.claim(command, false)).thenReturn(memberClaim);
        when(executionService.execute(command, claim)).thenReturn(createExecution(false));

        CreateDiaryResult result = diaryCreationService.create(command);

        assertThat(result.newlyCreated()).isFalse();
        assertThat(result.usage()).isEqualTo(usage);
        verify(transactionService).claim(command, false);
    }

    @Test
    @DisplayName("멱등성 키 경합으로 선점에 실패하면 경합에서 이긴 회원 요청을 복구한다")
    void recoverConcurrentClaim() {
        CreateDiaryCommand command = createCommand();
        GenerationUsage usage = new GenerationUsage(DIARY_DATE, 1, 3);
        DiaryCreationClaim claim = createClaim(false);
        MemberDiaryCreationClaim recoveredClaim = new MemberDiaryCreationClaim(claim, usage);
        DataIntegrityViolationException collision = new DataIntegrityViolationException("중복 멱등성 키");
        when(executionService.isGenerationAvailable()).thenReturn(true);
        when(transactionService.claim(command, true)).thenThrow(collision);
        when(transactionService.findExistingClaim(command)).thenReturn(Optional.of(recoveredClaim));
        when(executionService.execute(command, claim)).thenReturn(createExecution(false));

        CreateDiaryResult result = diaryCreationService.create(command);

        assertThat(result.newlyCreated()).isFalse();
        verify(transactionService).findExistingClaim(command);
    }

    @Test
    @DisplayName("복구할 회원 요청이 없으면 원래 무결성 예외를 전달한다")
    void propagateUnrelatedIntegrityViolation() {
        CreateDiaryCommand command = createCommand();
        DataIntegrityViolationException exception = new DataIntegrityViolationException("다른 제약 조건 위반");
        when(executionService.isGenerationAvailable()).thenReturn(true);
        when(transactionService.claim(command, true)).thenThrow(exception);
        when(transactionService.findExistingClaim(command)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> diaryCreationService.create(command)).isSameAs(exception);

        verify(transactionService).findExistingClaim(command);
    }

    private CreateDiaryCommand createCommand() {
        return new CreateDiaryCommand(
                USER_ID,
                DIARY_DATE,
                "오늘 친구와 카페에 갔다.",
                IDEMPOTENCY_KEY
        );
    }

    private DiaryCreationClaim createClaim(boolean newlyCreated) {
        return new DiaryCreationClaim(
                DIARY_ID,
                DIARY_DATE,
                "오늘 친구와 카페에 갔다.",
                CREATED_AT,
                GENERATION_ID,
                GenerationStatus.PROCESSING,
                null,
                null,
                null,
                null,
                newlyCreated
        );
    }

    private DiaryCreationExecution createExecution(boolean newlyCreated) {
        return new DiaryCreationExecution(
                DIARY_ID,
                DIARY_DATE,
                "오늘 친구와 카페에 갔다.",
                CREATED_AT,
                new DiaryGenerationResult(
                        GENERATION_ID,
                        GenerationStatus.SUCCEEDED,
                        "친구와 보낸 하루",
                        "generated/comic.png",
                        COMPLETED_AT
                ),
                newlyCreated
        );
    }
}
