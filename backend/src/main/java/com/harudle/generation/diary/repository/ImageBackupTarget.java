package com.harudle.generation.diary.repository;

import java.time.Instant;
import java.util.UUID;

/** 원본 백업에 필요한 키와 페이지 커서만 조회한다. */
public record ImageBackupTarget(UUID generationId, Instant completedAt, String imageObjectKey) {
}
