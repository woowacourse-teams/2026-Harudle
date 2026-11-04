package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateGuestDiaryCommand;
import com.harudle.diary.service.dto.CreateGuestDiaryResult;
import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
import com.harudle.generation.diary.service.DiaryGenerationExecutor;
import com.harudle.generation.diary.service.exception.DiaryGenerationFailedException;
import com.harudle.generation.diary.service.exception.GenerationUnavailableException;
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
class GuestDiaryCreationServiceTest {

    private static final UUID GUEST_USER_ID = UUID.fromString("a321bb09-816a-4941-906c-07b9c60db382");
    private static final UUID DIARY_ID = UUID.fromString("593363cb-1dc3-46bc-a858-5926f7601ca9");
    private static final UUID GENERATION_ID = UUID.fromString("17ac16ef-c45a-40bb-92ea-aed37659ef1c");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("7e5cc251-fdde-4cc0-a54e-2c8142750609");
    private static final String RAW_TOKEN = "guest-session-token";
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 20);
    private static final Instant CREATED_AT = Instant.parse("2026-08-20T00:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-20T00:01:00Z");
    private static final GenerationTokenUsage TOKEN_USAGE = new GenerationTokenUsage(120, 350, 80, 550);

    @Mock
    private GuestDiaryCreationTransactionService transactionService;

    @Mock
    private DiaryGenerationExecutor generationExecutor;

    private GuestDiaryCreationService guestDiaryCreationService;

    @BeforeEach
    void setUp() {
        guestDiaryCreationService = new GuestDiaryCreationService(transactionService, generationExecutor);
    }

    @AfterEach
    void doNotGenerateImageDuringRequest() {
        verify(generationExecutor, never()).generate(any(), any());
    }

    @Test
    @DisplayName("게스트 생성 요청은 이미지 생성 없이 대기 작업을 반환한다")
    void enqueueGuestDiary() {
        CreateGuestDiaryCommand command = createGuestCommand();
        DiaryCreationClaim claim = createClaim(GenerationStatus.PENDING, true);
        when(generationExecutor.isConfigured()).thenReturn(true);
        when(transactionService.enqueue(RAW_TOKEN, command, true)).thenReturn(guestClaim(command, claim));

        CreateGuestDiaryResult result = guestDiaryCreationService.create(RAW_TOKEN, command);

        assertThat(result.id()).isEqualTo(DIARY_ID);
        assertThat(result.diaryDate()).isEqualTo(DIARY_DATE);
        assertThat(result.sourceText()).isEqualTo(command.sourceText());
        assertThat(result.createdAt()).isEqualTo(CREATED_AT);
        assertThat(result.newlyCreated()).isTrue();
        assertThat(result.generation().id()).isEqualTo(GENERATION_ID);
        assertThat(result.generation().status()).isEqualTo(GenerationStatus.PENDING);
        assertThat(result.generation().title()).isNull();
        assertThat(result.generation().imageObjectKey()).isNull();
        assertThat(result.generation().completedAt()).isNull();
        assertThat(result.generation().tokenUsage()).isNull();
        verify(transactionService, never()).claim(anyString(), any(), anyBoolean());
    }

    @ParameterizedTest
    @EnumSource(value = GenerationStatus.class, names = {"PENDING", "PROCESSING", "SUCCEEDED"})
    @DisplayName("게스트 멱등 재요청은 어댑터 없이도 저장된 작업 상태와 결과를 반환한다")
    void returnExistingGuestDiary(GenerationStatus status) {
        CreateGuestDiaryCommand command = createGuestCommand();
        DiaryCreationClaim claim = createClaim(status, false);
        when(transactionService.enqueue(RAW_TOKEN, command, false)).thenReturn(guestClaim(command, claim));

        CreateGuestDiaryResult result = guestDiaryCreationService.create(RAW_TOKEN, command);

        assertThat(result.id()).isEqualTo(DIARY_ID);
        assertThat(result.newlyCreated()).isFalse();
        assertThat(result.generation().id()).isEqualTo(GENERATION_ID);
        assertThat(result.generation().status()).isEqualTo(status);
        assertThat(result.generation().title()).isEqualTo(claim.title());
        assertThat(result.generation().imageObjectKey()).isEqualTo(claim.imageObjectKey());
        assertThat(result.generation().completedAt()).isEqualTo(claim.completedAt());
        assertThat(result.generation().tokenUsage()).isEqualTo(claim.tokenUsage());
    }

    @Test
    @DisplayName("실패한 게스트 멱등 재요청은 저장된 오류 코드를 전달한다")
    void preserveFailedGuestGeneration() {
        CreateGuestDiaryCommand command = createGuestCommand();
        when(transactionService.enqueue(RAW_TOKEN, command, false))
                .thenReturn(guestClaim(command, createClaim(GenerationStatus.FAILED, false)));

        assertThatThrownBy(() -> guestDiaryCreationService.create(RAW_TOKEN, command))
                .isInstanceOfSatisfying(DiaryGenerationFailedException.class,
                        exception -> {
                            assertThat(exception.errorCode()).isEqualTo(GenerationErrorCode.AI_PROVIDER_TIMEOUT);
                            assertThat(exception.guest()).isTrue();
                        });
    }

    @Test
    @DisplayName("멱등성 키 경합으로 접수에 실패하면 기존 게스트 작업을 반환한다")
    void recoverConcurrentGuestEnqueue() {
        CreateGuestDiaryCommand command = createGuestCommand();
        GuestDiaryCreationClaim recovered = guestClaim(command, createClaim(GenerationStatus.PROCESSING, false));
        DataIntegrityViolationException collision = new DataIntegrityViolationException("중복 멱등성 키");
        when(generationExecutor.isConfigured()).thenReturn(true);
        when(transactionService.enqueue(RAW_TOKEN, command, true)).thenThrow(collision);
        when(transactionService.findExistingClaim(RAW_TOKEN, command)).thenReturn(Optional.of(recovered));

        CreateGuestDiaryResult result = guestDiaryCreationService.create(RAW_TOKEN, command);

        assertThat(result.newlyCreated()).isFalse();
        assertThat(result.generation().id()).isEqualTo(GENERATION_ID);
        assertThat(result.generation().status()).isEqualTo(GenerationStatus.PROCESSING);
        verify(transactionService).findExistingClaim(RAW_TOKEN, command);
    }

    @Test
    @DisplayName("복구할 게스트 요청이 없으면 원래 무결성 예외를 전달한다")
    void propagateUnrelatedIntegrityViolation() {
        CreateGuestDiaryCommand command = createGuestCommand();
        DataIntegrityViolationException exception = new DataIntegrityViolationException("다른 제약 조건 위반");
        when(generationExecutor.isConfigured()).thenReturn(true);
        when(transactionService.enqueue(RAW_TOKEN, command, true)).thenThrow(exception);
        when(transactionService.findExistingClaim(RAW_TOKEN, command)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guestDiaryCreationService.create(RAW_TOKEN, command)).isSameAs(exception);
    }

    @Test
    @DisplayName("어댑터가 준비되지 않은 신규 게스트 요청은 접수 실패를 전달한다")
    void rejectNewCreationWithoutGenerationAdapter() {
        CreateGuestDiaryCommand command = createGuestCommand();
        when(transactionService.enqueue(RAW_TOKEN, command, false))
                .thenThrow(GenerationUnavailableException.adaptersNotConfigured());

        assertThatThrownBy(() -> guestDiaryCreationService.create(RAW_TOKEN, command))
                .isInstanceOf(GenerationUnavailableException.class);
    }

    private GuestDiaryCreationClaim guestClaim(CreateGuestDiaryCommand command, DiaryCreationClaim claim) {
        CreateDiaryCommand diaryCommand = command.toDiaryCommand(GUEST_USER_ID);
        return new GuestDiaryCreationClaim(diaryCommand, claim);
    }

    private CreateGuestDiaryCommand createGuestCommand() {
        return new CreateGuestDiaryCommand(DIARY_DATE, "오늘 친구와 카페에 갔다.", IDEMPOTENCY_KEY);
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
