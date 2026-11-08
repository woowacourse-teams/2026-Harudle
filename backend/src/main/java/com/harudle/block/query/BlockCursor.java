package com.harudle.block.query;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 본인 발신 목록의 경계. 해당 차단 행을 해제한 뒤에도 다음 페이지를 읽을 수 있다. */
public record BlockCursor(UUID blockerId, Instant createdAt, UUID id) {
    public BlockCursor {
        Objects.requireNonNull(blockerId);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(id);
    }
}
