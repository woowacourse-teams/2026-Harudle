package com.harudle.moderation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.moderation.domain.*;
import com.harudle.moderation.repository.ModerationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminModerationServiceTest {
    private final UUID actor = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-23T03:00:00Z");
    private final ModerationRepository repository = mock(ModerationRepository.class);
    private final AdminModerationService service = new AdminModerationService(repository, Clock.fixed(now, ZoneOffset.UTC));
    private final ModerationTargetType type = ModerationTargetType.USER;

    @BeforeEach
    void administrator() {
        when(repository.isActiveAdmin(actor)).thenReturn(true);
        when(repository.targetExists(type, target)).thenReturn(true);
        when(repository.lockManageableTarget(type, target)).thenReturn(true);
    }

    @Test
    void storesFirstActionWithAuthenticatedActorAndClock() {
        when(repository.lockOrCreate(type, target)).thenReturn(ModerationState.initial(type, target));
        var changed = service.change(actor, type, target, new ModerationChange(true, 0, "  운영 사유  "));
        assertThat(changed).isEqualTo(new ModerationState(type, target, true, 1, "운영 사유", actor, now));
        verify(repository).saveChange(changed);
    }

    @Test
    void replayReturnsFirstActorAndTimestampWithoutAnotherAuditOrSessionRevocation() {
        var previous = new ModerationState(type, target, true, 1, "운영 사유", actor, now.minusSeconds(5));
        when(repository.lockOrCreate(type, target)).thenReturn(previous);
        assertThat(service.change(actor, type, target, new ModerationChange(true, 0, "운영 사유"))).isSameAs(previous);
        verify(repository, never()).saveChange(any());
    }

    @Test
    void replayFromBeforeReversalCannotSuspendAgain() {
        when(repository.lockOrCreate(type, target)).thenReturn(new ModerationState(type, target, false, 2, "해제", actor, now));
        assertThatThrownBy(() -> service.change(actor, type, target, new ModerationChange(true, 0, "최초 정지")))
                .isInstanceOf(ModerationConflictException.class);
        verify(repository, never()).saveChange(any());
    }

    @Test
    void anotherAdministratorsChangeMustBeReloaded() {
        when(repository.lockOrCreate(type, target)).thenReturn(new ModerationState(type, target, true, 1, "운영 사유", UUID.randomUUID(), now));
        assertThatThrownBy(() -> service.change(actor, type, target, new ModerationChange(true, 0, "운영 사유")))
                .isInstanceOf(ModerationConflictException.class);
        verify(repository, never()).saveChange(any());
    }

    @Test
    void matchingCurrentStateDoesNotReplaceReasonOrCreateHistory() {
        var state = new ModerationState(type, target, true, 1, "최초 사유", actor, now);
        when(repository.lockOrCreate(type, target)).thenReturn(state);
        assertThat(service.change(actor, type, target, new ModerationChange(true, 1, "다른 사유"))).isSameAs(state);
        verify(repository, never()).saveChange(any());
    }

    @Test
    void anonymousAndNonAdminDoNotReachTarget() {
        assertThatThrownBy(() -> service.change(null, type, target, new ModerationChange(true, 0, "정지")))
                .isInstanceOf(AuthenticationRequiredException.class);
        when(repository.isActiveAdmin(actor)).thenReturn(false);
        assertThatThrownBy(() -> service.find(actor, type, target)).isInstanceOf(ModerationAccessDeniedException.class);
        verify(repository, never()).lockManageableTarget(any(), any());
        verify(repository, never()).find(any(), any());
    }

    @Test
    void deletedOrAdminTargetIsNotMutable() {
        when(repository.lockManageableTarget(type, target)).thenReturn(false);
        assertThatThrownBy(() -> service.change(actor, type, target, new ModerationChange(true, 0, "정지")))
                .isInstanceOf(ModerationTargetNotFoundException.class);
        verify(repository, never()).lockOrCreate(any(), any());
    }

    @Test
    void absentTargetWithExistingHistoryIsStillReadable() {
        when(repository.targetExists(type, target)).thenReturn(false);
        var stored = new ModerationState(type, target, true, 1, "정지", actor, now);
        when(repository.find(type, target)).thenReturn(Optional.of(stored));
        when(repository.history(type, target, 0, 20)).thenReturn(List.of());
        assertThat(service.find(actor, type, target)).isSameAs(stored);
        assertThat(service.history(actor, type, target, 0, 20).content()).isEmpty();
    }
}
