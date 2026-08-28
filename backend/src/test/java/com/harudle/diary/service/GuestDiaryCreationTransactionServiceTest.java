package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.diary.service.dto.CreateGuestDiaryCommand;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.guest.application.GuestSessionResolver;
import com.harudle.guest.application.exception.GuestTrialAlreadyUsedException;
import com.harudle.guest.domain.GuestSession;
import java.time.Clock;
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
class GuestDiaryCreationTransactionServiceTest {

    private static final UUID GUEST_USER_ID = UUID.fromString("a321bb09-816a-4941-906c-07b9c60db382");
    private static final UUID DIARY_ID = UUID.fromString("593363cb-1dc3-46bc-a858-5926f7601ca9");
    private static final UUID GENERATION_ID = UUID.fromString("17ac16ef-c45a-40bb-92ea-aed37659ef1c");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("7e5cc251-fdde-4cc0-a54e-2c8142750609");
    private static final String RAW_TOKEN = "guest-session-token";
    private static final String TOKEN_HASH = "a".repeat(64);
    private static final Instant CREATED_AT = Instant.parse("2026-08-19T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-19T00:00:00Z");
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 20);

    @Mock
    private DiaryCreationClaimService claimService;

    @Mock
    private GuestSessionResolver guestSessionResolver;

    private GuestDiaryCreationTransactionService transactionService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        transactionService = new GuestDiaryCreationTransactionService(
                claimService,
                guestSessionResolver,
                clock
        );
    }

    @Test
    @DisplayName("게스트 신규 생성 선점은 세션을 일기에 한 번 사용한다")
    void claimNewGuestDiary() {
        GuestSession session = createSession();
        CreateGuestDiaryCommand guestCommand = createGuestCommand();
        DiaryCreationClaim claim = createClaim(true);
        when(guestSessionResolver.resolveForUpdate(RAW_TOKEN, NOW)).thenReturn(session);
        when(claimService.claim(guestCommand.toDiaryCommand(GUEST_USER_ID), true))
                .thenReturn(claim);

        GuestDiaryCreationClaim result = transactionService.claim(RAW_TOKEN, guestCommand, true);

        assertThat(result.claim()).isSameAs(claim);
        assertThat(result.command().userId()).isEqualTo(GUEST_USER_ID);
        assertThat(session.isUsedForDiary(DIARY_ID)).isTrue();
        assertThat(session.getUsedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("사용한 세션의 같은 멱등 요청은 기존 일기 선점을 반환한다")
    void replaySameGuestDiaryClaim() {
        GuestSession session = createUsedSession();
        CreateGuestDiaryCommand guestCommand = createGuestCommand();
        DiaryCreationClaim claim = createClaim(false);
        when(guestSessionResolver.resolveForUpdate(RAW_TOKEN, NOW)).thenReturn(session);
        when(claimService.findExistingClaim(guestCommand.toDiaryCommand(GUEST_USER_ID)))
                .thenReturn(Optional.of(claim));

        GuestDiaryCreationClaim result = transactionService.claim(RAW_TOKEN, guestCommand, false);

        assertThat(result.claim()).isSameAs(claim);
        verify(claimService, never()).claim(guestCommand.toDiaryCommand(GUEST_USER_ID), false);
        assertThat(session.getUsedAt()).isEqualTo(NOW.minusSeconds(1));
    }

    @Test
    @DisplayName("사용한 세션의 새로운 멱등 요청은 두 번째 생성을 거부한다")
    void rejectSecondGuestDiaryClaim() {
        GuestSession session = createUsedSession();
        CreateGuestDiaryCommand guestCommand = createGuestCommand();
        when(guestSessionResolver.resolveForUpdate(RAW_TOKEN, NOW)).thenReturn(session);
        when(claimService.findExistingClaim(guestCommand.toDiaryCommand(GUEST_USER_ID)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.claim(RAW_TOKEN, guestCommand, true))
                .isInstanceOf(GuestTrialAlreadyUsedException.class);

        verify(claimService, never()).claim(guestCommand.toDiaryCommand(GUEST_USER_ID), true);
    }

    @Test
    @DisplayName("멱등성 키 경합 복구 시 미사용 세션을 기존 일기에 연결한다")
    void recoverGuestSessionAssociation() {
        GuestSession session = createSession();
        CreateGuestDiaryCommand guestCommand = createGuestCommand();
        DiaryCreationClaim claim = createClaim(false);
        when(guestSessionResolver.resolveForUpdate(RAW_TOKEN, NOW)).thenReturn(session);
        when(claimService.findExistingClaim(guestCommand.toDiaryCommand(GUEST_USER_ID)))
                .thenReturn(Optional.of(claim));

        Optional<GuestDiaryCreationClaim> result = transactionService.findExistingClaim(
                RAW_TOKEN,
                guestCommand
        );

        assertThat(result).isPresent();
        assertThat(session.isUsedForDiary(DIARY_ID)).isTrue();
        assertThat(session.getUsedAt()).isEqualTo(NOW);
    }

    private GuestSession createSession() {
        return GuestSession.create(
                GUEST_USER_ID,
                TOKEN_HASH,
                EXPIRES_AT,
                CREATED_AT
        );
    }

    private GuestSession createUsedSession() {
        GuestSession session = createSession();
        session.useForDiary(DIARY_ID, NOW.minusSeconds(1));
        return session;
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
}
