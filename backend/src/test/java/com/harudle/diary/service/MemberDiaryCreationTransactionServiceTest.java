package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.usage.domain.GenerationUsage;
import com.harudle.generation.usage.service.GenerationUsageService;
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

@ExtendWith(MockitoExtension.class)
class MemberDiaryCreationTransactionServiceTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID DIARY_ID = UUID.fromString("6b66acba-0136-4822-8a59-f355dd7c977d");
    private static final UUID GENERATION_ID = UUID.fromString("17ac16ef-c45a-40bb-92ea-aed37659ef1c");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("7e5cc251-fdde-4cc0-a54e-2c8142750609");
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 6);
    private static final Instant CREATED_AT = Instant.parse("2026-08-06T11:00:00Z");

    @Mock
    private DiaryCreationClaimService claimService;

    @Mock
    private GenerationUsageService generationUsageService;

    private MemberDiaryCreationTransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new MemberDiaryCreationTransactionService(
                claimService,
                generationUsageService
        );
    }

    @Test
    @DisplayName("회원의 신규 생성 선점은 오늘 사용량을 한 번 증가시킨다")
    void incrementUsageForNewMemberClaim() {
        CreateDiaryCommand command = createCommand();
        DiaryCreationClaim claim = createClaim(true);
        GenerationUsage usage = new GenerationUsage(DIARY_DATE, 1, 3);
        when(claimService.claim(command, true)).thenReturn(claim);
        when(generationUsageService.incrementTodayUsage(USER_ID)).thenReturn(usage);

        MemberDiaryCreationClaim memberClaim = transactionService.claim(command, true);

        assertThat(memberClaim.claim()).isSameAs(claim);
        assertThat(memberClaim.usage()).isEqualTo(usage);
        verify(generationUsageService).incrementTodayUsage(USER_ID);
        verify(generationUsageService, never()).getTodayUsage(USER_ID);
    }

    @Test
    @DisplayName("회원의 기존 멱등 요청은 오늘 사용량을 조회하고 증가시키지 않는다")
    void readUsageForExistingMemberClaim() {
        CreateDiaryCommand command = createCommand();
        DiaryCreationClaim claim = createClaim(false);
        GenerationUsage usage = new GenerationUsage(DIARY_DATE, 1, 3);
        when(claimService.claim(command, false)).thenReturn(claim);
        when(generationUsageService.getTodayUsage(USER_ID)).thenReturn(usage);

        MemberDiaryCreationClaim memberClaim = transactionService.claim(command, false);

        assertThat(memberClaim.claim()).isSameAs(claim);
        assertThat(memberClaim.usage()).isEqualTo(usage);
        verify(generationUsageService).getTodayUsage(USER_ID);
        verify(generationUsageService, never()).incrementTodayUsage(USER_ID);
    }

    @Test
    @DisplayName("동시 선점에서 기존 요청을 찾으면 사용량을 조회하고 증가시키지 않는다")
    void readUsageForRecoveredMemberClaim() {
        CreateDiaryCommand command = createCommand();
        DiaryCreationClaim claim = createClaim(false);
        GenerationUsage usage = new GenerationUsage(DIARY_DATE, 1, 3);
        when(claimService.findExistingClaim(command)).thenReturn(Optional.of(claim));
        when(generationUsageService.getTodayUsage(USER_ID)).thenReturn(usage);

        Optional<MemberDiaryCreationClaim> memberClaim = transactionService.findExistingClaim(command);

        assertThat(memberClaim).contains(new MemberDiaryCreationClaim(claim, usage));
        verify(generationUsageService).getTodayUsage(USER_ID);
        verify(generationUsageService, never()).incrementTodayUsage(USER_ID);
    }

    @Test
    @DisplayName("동시 선점에서 기존 요청을 찾지 못하면 사용량을 조회하지 않는다")
    void doNotReadUsageWithoutRecoveredClaim() {
        CreateDiaryCommand command = createCommand();
        when(claimService.findExistingClaim(command)).thenReturn(Optional.empty());

        Optional<MemberDiaryCreationClaim> memberClaim = transactionService.findExistingClaim(command);

        assertThat(memberClaim).isEmpty();
        verifyNoInteractions(generationUsageService);
    }

    @Test
    @DisplayName("공통 생성 선점이 실패하면 회원 사용량을 변경하지 않는다")
    void doNotChangeUsageWhenClaimFails() {
        CreateDiaryCommand command = createCommand();
        IllegalStateException exception = new IllegalStateException("생성 선점 실패");
        when(claimService.claim(command, true)).thenThrow(exception);

        assertThatThrownBy(() -> transactionService.claim(command, true))
                .isSameAs(exception);

        verifyNoInteractions(generationUsageService);
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
}
