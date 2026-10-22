package com.harudle.block.service;

import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.block.domain.InvalidBlockException;
import com.harudle.block.domain.UserBlock;
import com.harudle.block.query.BlockCursor;
import com.harudle.block.repository.BlockRepository;
import com.harudle.block.repository.BlockSnapshot;
import com.harudle.block.service.dto.BlockPageResult;
import com.harudle.block.service.dto.BlockedUserResult;
import com.harudle.block.service.exception.BlockTargetNotFoundException;
import com.harudle.block.service.exception.BlockUnavailableException;
import com.harudle.profile.service.port.PublicProfileReader;
import com.harudle.profile.service.port.PublicProfileReader.Profile;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BlockService {
    private final BlockRepository repository;
    private final BlockInteractionPolicy policy;
    private final ObjectProvider<PublicProfileReader> profileReaders;
    private final BlockCursorCodec cursors;
    private final Clock clock;

    public BlockService(BlockRepository repository, BlockInteractionPolicy policy,
            ObjectProvider<PublicProfileReader> profileReaders, BlockCursorCodec cursors,
            @Qualifier("serviceClock") Clock clock) {
        this.repository = repository;
        this.policy = policy;
        this.profileReaders = profileReaders;
        this.cursors = cursors;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void block(UUID actorId, UUID targetId) {
        validateActorAndTarget(actorId, targetId);
        policy.lockAndIsBlocked(actorId, targetId);
        if (!repository.isActiveTarget(actorId, targetId)) {
            throw new BlockTargetNotFoundException();
        }
        repository.insert(new UserBlock(actorId, targetId, clock.instant()));
        repository.cancelUnsentBetween(actorId, targetId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void unblock(UUID actorId, UUID targetId) {
        validateActorAndTarget(actorId, targetId);
        policy.lockAndIsBlocked(actorId, targetId);
        // 상대의 현재 상태를 검사하지 않고 본인이 저장한 방향만 해제한다.
        repository.deleteOwn(actorId, targetId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BlockPageResult list(UUID actorId, @Nullable String cursor, int size) {
        validateMember(actorId);
        if (size < 1 || size > 50) {
            throw new IllegalArgumentException("차단 목록 크기는 1~50이어야 합니다.");
        }
        List<BlockSnapshot> found = repository.findAfter(actorId, cursors.decode(actorId, cursor), size + 1);
        boolean hasNext = found.size() > size;
        List<BlockSnapshot> page = found.stream().limit(size).toList();
        Set<UUID> activeIds = page.stream().filter(row -> !row.inactiveTarget()).map(BlockSnapshot::blockedId)
                .collect(Collectors.toSet());
        Map<UUID, Profile> profiles = activeIds.isEmpty() ? Map.of() : profilesFor(activeIds);
        List<BlockedUserResult> items = page.stream().map(row -> toResult(row, profiles)).toList();
        String nextCursor = hasNext ? cursors.encode(new BlockCursor(actorId, page.getLast().createdAt(),
                page.getLast().id())) : null;
        return new BlockPageResult(items, nextCursor, hasNext);
    }

    private void validateActorAndTarget(UUID actorId, UUID targetId) {
        validateMember(actorId);
        if (targetId == null || actorId.equals(targetId)) {
            throw new InvalidBlockException();
        }
    }

    private void validateMember(UUID actorId) {
        if (actorId == null || !repository.isActiveMember(actorId)) {
            throw new AuthenticationRequiredException();
        }
    }

    private Map<UUID, Profile> profilesFor(Set<UUID> ids) {
        PublicProfileReader reader = profileReaders.getIfAvailable();
        if (reader == null) {
            throw new BlockUnavailableException();
        }
        Map<UUID, Profile> profiles = reader.readAll(ids);
        if (profiles == null || ids.stream().anyMatch(id -> invalidProfile(id, profiles.get(id)))) {
            throw new BlockUnavailableException();
        }
        return profiles;
    }

    private static boolean invalidProfile(UUID id, @Nullable Profile profile) {
        return profile == null || !id.equals(profile.id()) || profile.nickname() == null
                || profile.nickname().isBlank() || profile.profileImageUrl() == null
                || !profile.profileImageUrl().isAbsolute() || profile.profileImageUrl().getHost() == null
                || !("https".equalsIgnoreCase(profile.profileImageUrl().getScheme())
                || "http".equalsIgnoreCase(profile.profileImageUrl().getScheme()));
    }

    private static BlockedUserResult toResult(BlockSnapshot row, Map<UUID, Profile> profiles) {
        if (row.inactiveTarget()) {
            return new BlockedUserResult(row.blockedId(), "탈퇴한 사용자", null, row.createdAt());
        }
        Profile profile = Objects.requireNonNull(profiles.get(row.blockedId()));
        return new BlockedUserResult(row.blockedId(), profile.nickname(), profile.profileImageUrl(), row.createdAt());
    }
}
