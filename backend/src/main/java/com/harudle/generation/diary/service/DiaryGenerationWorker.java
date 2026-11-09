package com.harudle.generation.diary.service;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.generation.config.GenerationWorkerProperties;
import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import com.harudle.generation.diary.service.exception.GenerationUnavailableException;
import com.harudle.generation.loadtest.LoadTestGenerationRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;

public final class DiaryGenerationWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiaryGenerationWorker.class);

    private final PendingDiaryGenerationClaimService claimService;
    private final DiaryGenerationRepository generationRepository;
    private final DiaryRepository diaryRepository;
    private final DiarySourceTextCipher sourceTextCipher;
    private final DiaryGenerationExecutor generationExecutor;
    private final Optional<LoadTestGenerationRegistry> loadTestGenerationRegistry;
    private final DiaryGenerationCompletionService completionService;
    private final TaskExecutor taskExecutor;
    private final int concurrency;
    private final Semaphore availableSlots;
    private final AtomicBoolean stopping = new AtomicBoolean();

    public DiaryGenerationWorker(
            PendingDiaryGenerationClaimService claimService,
            DiaryGenerationRepository generationRepository,
            DiaryRepository diaryRepository,
            DiarySourceTextCipher sourceTextCipher,
            DiaryGenerationExecutor generationExecutor,
            Optional<LoadTestGenerationRegistry> loadTestGenerationRegistry,
            DiaryGenerationCompletionService completionService,
            TaskExecutor taskExecutor,
            GenerationWorkerProperties properties
    ) {
        this.claimService = claimService;
        this.generationRepository = generationRepository;
        this.diaryRepository = diaryRepository;
        this.sourceTextCipher = sourceTextCipher;
        this.generationExecutor = generationExecutor;
        this.loadTestGenerationRegistry = loadTestGenerationRegistry;
        this.completionService = completionService;
        this.taskExecutor = taskExecutor;
        this.concurrency = properties.concurrency();
        this.availableSlots = new Semaphore(concurrency);
    }

    @Scheduled(
            fixedDelayString = "${harudle.generation.worker.poll-interval:5s}",
            initialDelayString = "${harudle.generation.worker.poll-interval:5s}"
    )
    public void poll() {
        if (stopping.get() || (!generationExecutor.isConfigured() && !isLoadTestConfigured())) {
            return;
        }
        for (int attempt = 0; attempt < concurrency; attempt++) {
            if (stopping.get() || !availableSlots.tryAcquire()) {
                return;
            }
            try {
                taskExecutor.execute(this::processNext);
            } catch (RejectedExecutionException exception) {
                availableSlots.release();
                LOGGER.atWarn()
                        .addKeyValue("event", "generation_worker_submission_rejected")
                        .log("event=generation_worker_submission_rejected");
                return;
            }
        }
    }

    public void requestStop() {
        stopping.set(true);
    }

    private void processNext() {
        UUID generationId = null;
        try {
            if (stopping.get()) {
                return;
            }
            List<UUID> claimedIds = claimService.claim(1);
            if (claimedIds.isEmpty()) {
                return;
            }
            generationId = claimedIds.getFirst();
            generate(generationId);
        } catch (RuntimeException exception) {
            if (generationId == null) {
                LOGGER.atWarn()
                        .addKeyValue("event", "generation_worker_claim_failed")
                        .addKeyValue("exceptionType", exception.getClass().getSimpleName())
                        .log("event=generation_worker_claim_failed exceptionType={}",
                                exception.getClass().getSimpleName());
            } else {
                handleFailure(generationId, exception);
            }
        } finally {
            if (generationId != null) {
                UUID completedGenerationId = generationId;
                loadTestGenerationRegistry.ifPresent(registry -> registry.remove(completedGenerationId));
            }
            availableSlots.release();
        }
    }

    private void generate(UUID generationId) {
        Optional<GenerateDiaryImageCommand> command = loadInputWithRetry(generationId);
        if (command.isEmpty()) {
            return;
        }
        DiaryGenerationExecutor executor = loadTestGenerationRegistry
                .flatMap(registry -> registry.findExecutor(generationId))
                .orElse(generationExecutor);
        if (!executor.isConfigured()) {
            throw GenerationUnavailableException.adaptersNotConfigured();
        }
        executor.generate(command.get(), generationId);
    }

    private Optional<GenerateDiaryImageCommand> loadInputWithRetry(UUID generationId) {
        try {
            return loadInput(generationId);
        } catch (TransientDataAccessException exception) {
            GenerationLogContext.withGenerationId(generationId, () -> LOGGER.atWarn()
                    .addKeyValue("event", "generation_worker_input_read_retry")
                    .addKeyValue("exceptionType", exception.getClass().getSimpleName())
                    .log("event=generation_worker_input_read_retry generationId={} exceptionType={}",
                            generationId, exception.getClass().getSimpleName()));
            return loadInput(generationId);
        }
    }

    private Optional<GenerateDiaryImageCommand> loadInput(UUID generationId) {
        DiaryGeneration generation = generationRepository.findById(generationId)
                .orElseThrow(() -> new IllegalStateException("선점한 생성 기록을 찾을 수 없습니다."));
        if (!generationId.equals(generation.getId())) {
            throw new IllegalStateException("선점한 생성 기록이 일치하지 않습니다.");
        }
        if (generation.getStatus() != GenerationStatus.PROCESSING) {
            return Optional.empty();
        }
        Diary diary = diaryRepository.findById(generation.getDiaryId())
                .orElseThrow(() -> new IllegalStateException("생성할 일기를 찾을 수 없습니다."));
        if (!generation.getDiaryId().equals(diary.getId()) || diary.getUserId() == null) {
            throw new IllegalStateException("생성할 일기 정보가 일치하지 않습니다.");
        }
        if (diary.isDeleted()) {
            throw new IllegalStateException("삭제된 일기는 생성할 수 없습니다.");
        }
        return Optional.of(new GenerateDiaryImageCommand(
                diary.getUserId(),
                diary.getId(),
                diary.getDiaryDate(),
                diary.decryptSourceText(sourceTextCipher),
                generation.getIdempotencyKey()
        ));
    }

    private boolean isLoadTestConfigured() {
        return loadTestGenerationRegistry.map(LoadTestGenerationRegistry::isConfigured).orElse(false);
    }

    private void handleFailure(UUID generationId, RuntimeException exception) {
        try {
            generationRepository.findById(generationId)
                    .filter(generation -> generation.getStatus() == GenerationStatus.PROCESSING)
                    .ifPresent(generation -> completionService.fail(
                            generationId, GenerationErrorCode.GENERATION_INTERNAL_ERROR
                    ));
        } catch (RuntimeException persistenceException) {
            GenerationLogContext.withGenerationId(generationId, () -> LOGGER.atError()
                    .addKeyValue("event", "generation_worker_failure_persistence_failed")
                    .addKeyValue("exceptionType", persistenceException.getClass().getSimpleName())
                    .log("event=generation_worker_failure_persistence_failed generationId={} exceptionType={}",
                            generationId, persistenceException.getClass().getSimpleName()));
        }
        GenerationLogContext.withGenerationId(generationId, () -> LOGGER.atWarn()
                .addKeyValue("event", "generation_worker_execution_failed")
                .addKeyValue("exceptionType", exception.getClass().getSimpleName())
                .log("event=generation_worker_execution_failed generationId={} exceptionType={}",
                        generationId, exception.getClass().getSimpleName()));
    }
}
