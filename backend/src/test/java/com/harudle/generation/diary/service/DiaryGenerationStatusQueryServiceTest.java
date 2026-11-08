package com.harudle.generation.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationQueryRepository;
import com.harudle.generation.diary.repository.DiaryGenerationStatusSnapshot;
import com.harudle.generation.diary.service.dto.DiaryGenerationStatusResult;
import com.harudle.generation.diary.service.exception.DiaryGenerationNotFoundException;
import com.harudle.guest.application.GuestSessionResolver;
import com.harudle.guest.application.exception.GuestSessionExpiredException;
import com.harudle.guest.domain.GuestSession;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DiaryGenerationStatusQueryServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID DIARY_ID = UUID.randomUUID();
    private static final UUID GENERATION_ID = UUID.randomUUID();
    private static final String TOKEN = "guest-session-token";
    private static final Instant NOW = Instant.parse("2026-10-20T06:00:00Z");

    @Mock private DiaryGenerationQueryRepository repository;
    @Mock private GuestSessionResolver sessionResolver;
    private DiaryGenerationStatusQueryService service;

    @BeforeEach
    void setUp() {
        service = new DiaryGenerationStatusQueryService(repository, sessionResolver, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @EnumSource(GenerationStatus.class)
    @DisplayName("본인의 작업 상태를 읽고 성공 시 일기 ID와 실패 시 오류 코드를 반환한다")
    void getOwnedGenerationStatus(GenerationStatus status) {
        when(repository.findStatusSnapshotByIdAndUserId(GENERATION_ID, USER_ID))
                .thenReturn(Optional.of(snapshot(status, DIARY_ID)));

        DiaryGenerationStatusResult result = service.getStatus(USER_ID, GENERATION_ID);

        assertStatus(result, status);
        verifyNoInteractions(sessionResolver);
    }

    @Test
    @DisplayName("다른 사용자의 작업과 존재하지 않는 작업은 같은 오류로 반환한다")
    void rejectMissingOrUnownedGeneration() {
        when(repository.findStatusSnapshotByIdAndUserId(GENERATION_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStatus(USER_ID, GENERATION_ID))
                .isInstanceOf(DiaryGenerationNotFoundException.class);
        verify(repository).findStatusSnapshotByIdAndUserId(GENERATION_ID, USER_ID);
    }

    @ParameterizedTest
    @EnumSource(GenerationStatus.class)
    @DisplayName("게스트 세션의 소유자와 연결 일기를 모두 확인하고 상태를 반환한다")
    void getLinkedGuestGenerationStatus(GenerationStatus status) {
        when(sessionResolver.resolve(TOKEN, NOW)).thenReturn(usedSession());
        when(repository.findStatusSnapshotByIdAndUserId(GENERATION_ID, USER_ID))
                .thenReturn(Optional.of(snapshot(status, DIARY_ID)));

        assertStatus(service.getGuestStatus(TOKEN, GENERATION_ID), status);
        verify(repository).findStatusSnapshotByIdAndUserId(GENERATION_ID, USER_ID);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("회원과 게스트에게 대기 만료 상태와 오류를 반환하고 일기 ID는 제공하지 않는다")
    void getPendingExpiredGenerationStatus(boolean guest) {
        when(repository.findStatusSnapshotByIdAndUserId(GENERATION_ID, USER_ID))
                .thenReturn(Optional.of(new DiaryGenerationStatusSnapshot(
                        GENERATION_ID, DIARY_ID, GenerationStatus.FAILED, GenerationErrorCode.GENERATION_PENDING_TIMEOUT
                )));
        if (guest) {
            when(sessionResolver.resolve(TOKEN, NOW)).thenReturn(usedSession());
        }

        DiaryGenerationStatusResult result = guest
                ? service.getGuestStatus(TOKEN, GENERATION_ID)
                : service.getStatus(USER_ID, GENERATION_ID);

        assertThat(result.id()).isEqualTo(GENERATION_ID);
        assertThat(result.status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(result.diaryId()).isNull();
        assertThat(result.errorCode()).isEqualTo(GenerationErrorCode.GENERATION_PENDING_TIMEOUT);
        verify(repository).findStatusSnapshotByIdAndUserId(GENERATION_ID, USER_ID);
    }

    @Test
    @DisplayName("같은 게스트 사용자의 작업이어도 현재 세션에 연결되지 않았으면 조회하지 못한다")
    void rejectUnlinkedGuestGeneration() {
        when(sessionResolver.resolve(TOKEN, NOW)).thenReturn(usedSession());
        when(repository.findStatusSnapshotByIdAndUserId(GENERATION_ID, USER_ID))
                .thenReturn(Optional.of(snapshot(GenerationStatus.PENDING, UUID.randomUUID())));

        assertThatThrownBy(() -> service.getGuestStatus(TOKEN, GENERATION_ID))
                .isInstanceOf(DiaryGenerationNotFoundException.class);
    }

    @Test
    @DisplayName("대기 만료된 작업도 현재 게스트 세션에 연결되지 않았으면 조회하지 못한다")
    void rejectUnlinkedPendingExpiredGuestGeneration() {
        when(sessionResolver.resolve(TOKEN, NOW)).thenReturn(usedSession());
        when(repository.findStatusSnapshotByIdAndUserId(GENERATION_ID, USER_ID))
                .thenReturn(Optional.of(new DiaryGenerationStatusSnapshot(
                        GENERATION_ID, UUID.randomUUID(), GenerationStatus.FAILED,
                        GenerationErrorCode.GENERATION_PENDING_TIMEOUT
                )));

        assertThatThrownBy(() -> service.getGuestStatus(TOKEN, GENERATION_ID))
                .isInstanceOf(DiaryGenerationNotFoundException.class);
    }

    @Test
    @DisplayName("게스트에게 다른 소유자의 작업 정보를 노출하지 않는다")
    void rejectForeignGuestGeneration() {
        when(sessionResolver.resolve(TOKEN, NOW)).thenReturn(usedSession());
        when(repository.findStatusSnapshotByIdAndUserId(GENERATION_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getGuestStatus(TOKEN, GENERATION_ID))
                .isInstanceOf(DiaryGenerationNotFoundException.class);
    }

    @Test
    @DisplayName("아직 사용하지 않은 게스트 세션은 작업을 조회하지 않는다")
    void rejectUnusedGuestSession() {
        when(sessionResolver.resolve(TOKEN, NOW)).thenReturn(session());

        assertThatThrownBy(() -> service.getGuestStatus(TOKEN, GENERATION_ID))
                .isInstanceOf(DiaryGenerationNotFoundException.class);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("만료된 게스트 세션은 작업을 조회하기 전에 거절한다")
    void rejectExpiredGuestSessionBeforeQuery() {
        when(sessionResolver.resolve(TOKEN, NOW)).thenThrow(new GuestSessionExpiredException());

        assertThatThrownBy(() -> service.getGuestStatus(TOKEN, GENERATION_ID))
                .isInstanceOf(GuestSessionExpiredException.class);
        verifyNoInteractions(repository);
    }

    private void assertStatus(DiaryGenerationStatusResult result, GenerationStatus status) {
        assertThat(result.id()).isEqualTo(GENERATION_ID);
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.diaryId()).isEqualTo(status == GenerationStatus.SUCCEEDED ? DIARY_ID : null);
        assertThat(result.errorCode()).isEqualTo(status == GenerationStatus.FAILED
                ? GenerationErrorCode.AI_PROVIDER_TIMEOUT : null);
    }

    private DiaryGenerationStatusSnapshot snapshot(GenerationStatus status, UUID diaryId) {
        return new DiaryGenerationStatusSnapshot(GENERATION_ID, diaryId, status,
                status == GenerationStatus.FAILED ? GenerationErrorCode.AI_PROVIDER_TIMEOUT : null);
    }

    private GuestSession usedSession() {
        GuestSession session = session();
        session.useForDiary(DIARY_ID, NOW.minusSeconds(1));
        return session;
    }

    private GuestSession session() {
        return GuestSession.create(USER_ID, "a".repeat(64), NOW.plusSeconds(3600), NOW.minusSeconds(60));
    }
}
