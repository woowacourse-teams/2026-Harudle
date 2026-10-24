package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateGuestDiaryCommand;
import com.harudle.diary.service.dto.CreateGuestDiaryResult;
import com.harudle.diary.service.dto.DiaryGenerationResult;
import com.harudle.generation.diary.domain.GenerationStatus;
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
class GuestDiaryCreationServiceTest {

    private static final UUID GUEST_USER_ID = UUID.fromString("a321bb09-816a-4941-906c-07b9c60db382");
    private static final UUID DIARY_ID = UUID.fromString("593363cb-1dc3-46bc-a858-5926f7601ca9");
    private static final UUID GENERATION_ID = UUID.fromString("17ac16ef-c45a-40bb-92ea-aed37659ef1c");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("7e5cc251-fdde-4cc0-a54e-2c8142750609");
    private static final String RAW_TOKEN = "guest-session-token";
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 20);
    private static final Instant CREATED_AT = Instant.parse("2026-08-20T00:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-20T00:01:00Z");

    @Mock
    private GuestDiaryCreationTransactionService transactionService;

    @Mock
    private DiaryCreationExecutionService executionService;

    private GuestDiaryCreationService guestDiaryCreationService;

    @BeforeEach
    void setUp() {
        guestDiaryCreationService = new GuestDiaryCreationService(
                transactionService,
                executionService
        );
    }

    @Test
    @DisplayName("게스트 생성 선점과 공통 실행 결과를 사용량 없이 반환한다")
    void createGuestDiary() {
        CreateGuestDiaryCommand guestCommand = createGuestCommand();
        CreateDiaryCommand command = guestCommand.toDiaryCommand(GUEST_USER_ID);
        DiaryCreationClaim claim = createClaim(true);
        when(executionService.isGenerationAvailable()).thenReturn(true);
        when(transactionService.claim(RAW_TOKEN, guestCommand, true))
                .thenReturn(new GuestDiaryCreationClaim(command, claim));
        when(executionService.execute(command, claim)).thenReturn(createExecution(true));

        CreateGuestDiaryResult result = guestDiaryCreationService.create(RAW_TOKEN, guestCommand);

        assertThat(result.id()).isEqualTo(DIARY_ID);
        assertThat(result.newlyCreated()).isTrue();
        assertThat(result.generation().title()).isEqualTo("친구와 보낸 하루");
    }

    @Test
    @DisplayName("멱등성 키 경합으로 선점에 실패하면 기존 게스트 요청을 복구한다")
    void recoverConcurrentGuestClaim() {
        CreateGuestDiaryCommand guestCommand = createGuestCommand();
        CreateDiaryCommand command = guestCommand.toDiaryCommand(GUEST_USER_ID);
        DiaryCreationClaim claim = createClaim(false);
        GuestDiaryCreationClaim recoveredClaim = new GuestDiaryCreationClaim(command, claim);
        DataIntegrityViolationException collision = new DataIntegrityViolationException("중복 멱등성 키");
        when(executionService.isGenerationAvailable()).thenReturn(true);
        when(transactionService.claim(RAW_TOKEN, guestCommand, true)).thenThrow(collision);
        when(transactionService.findExistingClaim(RAW_TOKEN, guestCommand))
                .thenReturn(Optional.of(recoveredClaim));
        when(executionService.execute(command, claim)).thenReturn(createExecution(false));

        CreateGuestDiaryResult result = guestDiaryCreationService.create(RAW_TOKEN, guestCommand);

        assertThat(result.newlyCreated()).isFalse();
        verify(transactionService).findExistingClaim(RAW_TOKEN, guestCommand);
    }

    @Test
    @DisplayName("복구할 게스트 요청이 없으면 원래 무결성 예외를 전달한다")
    void propagateUnrelatedIntegrityViolation() {
        CreateGuestDiaryCommand guestCommand = createGuestCommand();
        DataIntegrityViolationException exception = new DataIntegrityViolationException("다른 제약 조건 위반");
        when(executionService.isGenerationAvailable()).thenReturn(true);
        when(transactionService.claim(RAW_TOKEN, guestCommand, true)).thenThrow(exception);
        when(transactionService.findExistingClaim(RAW_TOKEN, guestCommand))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> guestDiaryCreationService.create(RAW_TOKEN, guestCommand))
                .isSameAs(exception);
    }

    private CreateGuestDiaryCommand createGuestCommand() {
        return new CreateGuestDiaryCommand(
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
