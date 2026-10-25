package com.harudle.generation.diary.service.dto;

import java.time.Instant;
import java.util.UUID;

public record ImageBackupBatchResult(UUID runId, Instant cutoff, Status status, long processedCount,
        long uploadedCount, long existingVerifiedCount, long originalMissingCount, long failedCount) {
    public enum Status {
        COMPLETED, PARTIAL_FAILURE, INTERRUPTED, FAILED
    }
}
