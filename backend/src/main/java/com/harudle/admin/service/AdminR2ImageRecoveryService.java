package com.harudle.admin.service;

import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.ImageRecoveryService;
import com.harudle.generation.diary.service.dto.ImageRecoveryResult;
import com.harudle.generation.diary.service.exception.ImageRecoveryException;
import com.harudle.generation.diary.service.port.BackupStorageException;
import com.harudle.generation.diary.service.port.ImageStorageException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminR2ImageRecoveryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdminR2ImageRecoveryService.class);
    private final DiaryGenerationRepository generations;
    private final ObjectProvider<ImageRecoveryService> recoveries;
    private final RecoveryExecutionGate gate;

    public AdminR2ImageRecoveryService(DiaryGenerationRepository generations,
            ObjectProvider<ImageRecoveryService> recoveries, RecoveryExecutionGate gate) {
        this.generations = generations;
        this.recoveries = recoveries;
        this.gate = gate;
    }

    public BatchResult recover(String environment, List<UUID> generationIds, boolean dryRun) {
        if (generationIds == null || generationIds.isEmpty() || generationIds.size() > 100
                || generationIds.stream().anyMatch(java.util.Objects::isNull)
                || generationIds.stream().distinct().count() != generationIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "중복 없는 생성 기록 UUID를 1~100개 지정하세요.");
        }
        ImageRecoveryService recovery = recoveries.getIfAvailable();
        if (recovery == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "S3와 R2 복구 설정이 필요합니다.");
        }
        if (!recovery.environment().equals(environment)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "요청 환경과 서버의 복구 환경이 다릅니다.");
        }
        UUID batchId = UUID.randomUUID();
        List<TargetResult> results = new ArrayList<>();
        try {
            for (UUID id : generationIds) {
                // dry-run은 실행 간격 대기 없이 조회만 수행한다. 실제 복구는 기존 복구 잠금을 공유한다.
                TargetResult result = dryRun ? recoverTarget(id, recovery, true)
                        : gate.execute(() -> recoverTarget(id, recovery, false));
                results.add(result);
                LOGGER.info("event=admin_r2_recovery_target batchId={} environment={} dryRun={} "
                                + "generationId={} status={} failureCode={}",
                        batchId, environment, dryRun, id, result.status(), result.failureCode());
            }
            return new BatchResult(batchId, environment, dryRun, results);
        } finally {
            LOGGER.info("event=admin_r2_recovery_batch batchId={} environment={} dryRun={} "
                            + "generationIds={} completed={} requested={}",
                    batchId, environment, dryRun, generationIds, results.size(), generationIds.size());
        }
    }

    private TargetResult recoverTarget(UUID id, ImageRecoveryService recovery, boolean dryRun) {
        var generation = generations.findById(id).orElse(null);
        if (generation == null) {
            return TargetResult.failed(id, "GENERATION_NOT_FOUND");
        }
        String key = generation.getImageObjectKey();
        if (generation.getStatus() != GenerationStatus.SUCCEEDED || key == null || key.isBlank()) {
            return TargetResult.failed(id, "GENERATION_NOT_RECOVERABLE");
        }
        try {
            ImageRecoveryResult result = recovery.recover(key, dryRun);
            return new TargetResult(id, result.status(), result, null);
        } catch (ImageRecoveryException | BackupStorageException | ImageStorageException
                | IllegalArgumentException exception) {
            return TargetResult.failed(id, ImageRecoveryService.failureCode(exception));
        }
    }

    public record BatchResult(UUID batchId, String environment, boolean dryRun, List<TargetResult> results) {
        public BatchResult {
            results = List.copyOf(results);
        }
    }

    public record TargetResult(UUID generationId, String status, @Nullable ImageRecoveryResult recovery,
            @Nullable String failureCode) {
        static TargetResult failed(UUID id, String code) {
            return new TargetResult(id, "FAILED", null, code);
        }
    }
}
