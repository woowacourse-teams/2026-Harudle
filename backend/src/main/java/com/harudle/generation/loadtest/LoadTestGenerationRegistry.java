package com.harudle.generation.loadtest;

import com.harudle.generation.diary.service.DiaryGenerationExecutor;
import com.harudle.generation.diary.service.event.DiaryGenerationPendingExpiredEvent;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class LoadTestGenerationRegistry {

    private final Set<UUID> generationIds = ConcurrentHashMap.newKeySet();
    private final LoadTestDiaryGenerationExecutor executor;

    public LoadTestGenerationRegistry(LoadTestDiaryGenerationExecutor executor) {
        this.executor = executor;
    }

    public void register(UUID generationId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("테스트 작업은 저장 트랜잭션 안에서 등록해야 합니다.");
        }
        if (generationIds.add(generationId)) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        generationIds.remove(generationId);
                    }
                }
            });
        }
    }

    public Optional<DiaryGenerationExecutor> findExecutor(UUID generationId) {
        return generationIds.contains(generationId) ? Optional.of(executor) : Optional.empty();
    }

    public void remove(UUID generationId) {
        generationIds.remove(generationId);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPendingExpired(DiaryGenerationPendingExpiredEvent event) {
        remove(event.generationId());
    }

    public boolean isConfigured() {
        return executor.isConfigured();
    }
}
