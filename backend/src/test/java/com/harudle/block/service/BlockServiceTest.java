package com.harudle.block.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.block.domain.InvalidBlockException;
import com.harudle.block.domain.UserBlock;
import com.harudle.block.repository.BlockRepository;
import com.harudle.block.repository.BlockSnapshot;
import com.harudle.block.service.exception.BlockTargetNotFoundException;
import com.harudle.block.service.exception.BlockUnavailableException;
import com.harudle.profile.service.port.PublicProfileReader;
import com.harudle.profile.service.port.PublicProfileReader.Profile;
import com.harudle.push.service.port.FeedPushPublicationGuard;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class BlockServiceTest {
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
    @Mock private BlockRepository repository;
    @Mock private BlockInteractionPolicy policy;
    @Mock private FeedPushPublicationGuard publicationGuard;
    @Mock private ObjectProvider<PublicProfileReader> readers;
    @Mock private PublicProfileReader profiles;
    private BlockService service;

    @BeforeEach
    void setUp() {
        service = new BlockService(repository, policy, publicationGuard, readers, new BlockCursorCodec(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("차단은 동일 쌍 잠금 뒤 본인 방향만 저장하고 기존 미발송 작업을 취소한다")
    void savesDirectionAndCancelsUnsentJobsUnderPairLock() {
        when(repository.isActiveMember(ACTOR)).thenReturn(true);
        when(repository.isActiveTarget(ACTOR, TARGET)).thenReturn(true);
        service.block(ACTOR, TARGET);
        var order = inOrder(policy, repository, publicationGuard);
        order.verify(repository).isActiveMember(ACTOR);
        order.verify(policy).lockPair(ACTOR, TARGET);
        order.verify(repository).isActiveTarget(ACTOR, TARGET);
        order.verify(publicationGuard).lockForBlock(ACTOR, TARGET);
        var saved = ArgumentCaptor.forClass(UserBlock.class);
        order.verify(repository).insert(saved.capture());
        order.verify(repository).cancelUnsentBetween(ACTOR, TARGET);
        assertThat(saved.getValue().getBlockerId()).isEqualTo(ACTOR);
        assertThat(saved.getValue().getBlockedId()).isEqualTo(TARGET);
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("비회원은 잠금과 조회 전에 거절하고 자기 차단은 저장하지 않는다")
    void rejectsInactiveActorAndSelfBeforeLock() {
        assertThatThrownBy(() -> service.block(null, TARGET)).isInstanceOf(AuthenticationRequiredException.class);
        assertThatThrownBy(() -> service.unblock(ACTOR, TARGET)).isInstanceOf(AuthenticationRequiredException.class);
        when(repository.isActiveMember(ACTOR)).thenReturn(true);
        assertThatThrownBy(() -> service.block(ACTOR, ACTOR)).isInstanceOf(InvalidBlockException.class);
        verifyNoInteractions(policy, publicationGuard);
        verify(repository, never()).insert(any());
    }

    @Test
    @DisplayName("대상이 없거나 비활성이면 새 차단과 푸시 취소를 수행하지 않는다")
    void rejectsInactiveTarget() {
        when(repository.isActiveMember(ACTOR)).thenReturn(true);
        assertThatThrownBy(() -> service.block(ACTOR, TARGET)).isInstanceOf(BlockTargetNotFoundException.class);
        verify(repository, never()).insert(any());
        verify(repository, never()).cancelUnsentBetween(any(), any());
        verifyNoInteractions(publicationGuard);
    }

    @Test
    @DisplayName("해제는 대상이 탈퇴했거나 상대가 차단했어도 본인 방향만 삭제한다")
    void unblocksOwnDirectionWithoutRequiringActiveTarget() {
        when(repository.isActiveMember(ACTOR)).thenReturn(true);
        service.unblock(ACTOR, TARGET);
        var order = inOrder(policy, repository, publicationGuard);
        order.verify(repository).isActiveMember(ACTOR);
        order.verify(policy).lockPair(ACTOR, TARGET);
        order.verify(repository).deleteOwn(ACTOR, TARGET);
        verify(repository, never()).isActiveTarget(any(), any());
        verify(repository, never()).deleteOwn(TARGET, ACTOR);
        verifyNoInteractions(publicationGuard);
    }

    @Test
    @DisplayName("실제 반환 페이지의 활성 프로필만 묶어 읽고 탈퇴 대상은 익명화하며 다음 경계를 제공한다")
    void listsOwnedPageAndAnonymizesInactiveTarget() {
        UUID deleted = UUID.randomUUID();
        UUID extra = UUID.randomUUID();
        when(repository.isActiveMember(ACTOR)).thenReturn(true);
        when(repository.findAfter(eq(ACTOR), any(), eq(3))).thenReturn(List.of(
                row(TARGET, false), row(deleted, true), row(extra, false)));
        when(readers.getIfAvailable()).thenReturn(profiles);
        Profile targetProfile = profile(TARGET);
        when(profiles.readAll(Set.of(TARGET))).thenReturn(Map.of(TARGET, targetProfile));
        var page = service.list(ACTOR, null, 2);
        assertThat(page.items()).extracting(item -> item.userId()).containsExactly(TARGET, deleted);
        assertThat(page.items().get(1).nickname()).isEqualTo("탈퇴한 사용자");
        assertThat(page.items().get(1).profileImageUrl()).isNull();
        assertThat(page.hasNext()).isTrue();
        assertThat(new BlockCursorCodec().decode(ACTOR, page.nextCursor())).hasValueSatisfying(
                cursor -> assertThat(cursor.blockerId()).isEqualTo(ACTOR));
        verify(profiles).readAll(Set.of(TARGET));
    }

    @Test
    @DisplayName("탈퇴 대상만 남은 목록은 프로필 서비스 없이 해제 가능한 ID를 반환한다")
    void listsInactiveTargetsWithoutProfiles() {
        when(repository.isActiveMember(ACTOR)).thenReturn(true);
        when(repository.findAfter(eq(ACTOR), any(), anyInt())).thenReturn(List.of(row(TARGET, true)));
        assertThat(service.list(ACTOR, null, 20).items()).singleElement()
                .satisfies(item -> assertThat(item.userId()).isEqualTo(TARGET));
        verifyNoInteractions(readers, profiles);
    }

    @Test
    @DisplayName("활성 대상의 프로필 누락은 조용히 누락시키지 않고 일시 장애로 반환한다")
    void rejectsUnavailableActiveProfile() {
        when(repository.isActiveMember(ACTOR)).thenReturn(true);
        when(repository.findAfter(eq(ACTOR), any(), anyInt())).thenReturn(List.of(row(TARGET, false)));
        assertThatThrownBy(() -> service.list(ACTOR, null, 20)).isInstanceOf(BlockUnavailableException.class);
    }

    private BlockSnapshot row(UUID target, boolean inactive) {
        return new BlockSnapshot(UUID.randomUUID(), target, NOW, inactive);
    }

    private Profile profile(UUID userId) {
        Profile profile = mock(Profile.class);
        when(profile.id()).thenReturn(userId);
        when(profile.nickname()).thenReturn("차단한 회원");
        when(profile.profileImageUrl()).thenReturn(URI.create("https://example.com/profile.svg"));
        return profile;
    }
}
