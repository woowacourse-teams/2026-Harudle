package com.harudle.block.repository;

import com.harudle.block.domain.UserBlock;
import com.harudle.block.query.BlockCursor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BlockRepository {
    boolean isActiveMember(UUID userId);

    boolean isActiveTarget(UUID actorId, UUID targetId);

    void lockPair(long key);

    boolean existsBetween(UUID first, UUID second);

    boolean insert(UserBlock block);

    void deleteOwn(UUID blockerId, UUID blockedId);

    void cancelUnsentBetween(UUID first, UUID second);

    List<BlockSnapshot> findAfter(UUID blockerId, Optional<BlockCursor> cursor, int limit);
}
