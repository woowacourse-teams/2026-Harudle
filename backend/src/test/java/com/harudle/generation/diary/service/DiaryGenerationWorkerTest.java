package com.harudle.generation.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.generation.config.GenerationWorkerProperties;
import com.harudle.generation.loadtest.LoadTestGenerationRegistry;
import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(OutputCaptureExtension.class)
class DiaryGenerationWorkerTest {

    private PendingDiaryGenerationClaimService claimService;
    private DiaryGenerationRepository generationRepository;
    private DiaryRepository diaryRepository;
    private DiaryGenerationExecutor generationExecutor;
    private DiaryGenerationCompletionService completionService;

    @BeforeEach
    void setUp() {
        claimService = mock(PendingDiaryGenerationClaimService.class);
        generationRepository = mock(DiaryGenerationRepository.class);
        diaryRepository = mock(DiaryRepository.class);
        generationExecutor = mock(DiaryGenerationExecutor.class);
        completionService = mock(DiaryGenerationCompletionService.class);
        when(generationExecutor.isConfigured()).thenReturn(true);
    }

    @Test
    @DisplayName("실행 스레드가 작업을 시작한 뒤 선점하고 DB에 저장된 입력으로 생성한다")
    void claimInsideAcceptedTaskAndUsePersistedInput() {
        Fixture fixture = processingGeneration();
        stubInput(fixture);
        when(claimService.claim(1)).thenReturn(List.of(fixture.generation().getId()));
        Queue<Runnable> tasks = new ArrayDeque<>();
        DiaryGenerationWorker worker = worker(tasks::add, 1);

        worker.poll();

        verifyNoInteractions(claimService, generationRepository, diaryRepository);
        assertThat(tasks).hasSize(1);

        tasks.remove().run();

        Diary diary = fixture.diary();
        verify(generationExecutor).generate(new GenerateDiaryImageCommand(
                diary.getUserId(), diary.getId(), diary.getDiaryDate(), diary.getSourceText(), fixture.requestKey()
        ), fixture.generation().getId());
        verifyNoInteractions(completionService);
    }

    @Test
    @DisplayName("생성 어댑터가 준비되지 않으면 실행 요청과 선점을 하지 않는다")
    void doNotClaimWithoutConfiguredAdapters() {
        when(generationExecutor.isConfigured()).thenReturn(false);
        TaskExecutor taskExecutor = mock(TaskExecutor.class);

        worker(taskExecutor, 2).poll();

        verifyNoInteractions(taskExecutor, claimService, generationRepository, diaryRepository);
    }

    @Test
    @DisplayName("대기 작업이 없으면 한 번의 확인에서 설정한 개수까지만 조회하고 다음에도 확인한다")
    void boundEmptyPollsAndReleaseSlots() {
        when(claimService.claim(1)).thenReturn(List.of());
        DiaryGenerationWorker worker = worker(Runnable::run, 2);

        worker.poll();
        worker.poll();

        verify(claimService, times(4)).claim(1);
        verify(generationExecutor, never()).generate(any(), any());
        verifyNoInteractions(generationRepository, diaryRepository, completionService);
    }

    @Test
    @DisplayName("실행 요청이 거절되면 선점하지 않고 다음 확인에서 다시 실행할 수 있다")
    void releaseSlotAfterSubmissionRejection() {
        TaskExecutor taskExecutor = mock(TaskExecutor.class);
        AtomicInteger submissions = new AtomicInteger();
        doAnswer(invocation -> {
            if (submissions.getAndIncrement() == 0) {
                throw new TaskRejectedException("테스트 실행 요청 거절");
            }
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(taskExecutor).execute(any());
        when(claimService.claim(1)).thenReturn(List.of());
        DiaryGenerationWorker worker = worker(taskExecutor, 1);

        worker.poll();
        verifyNoInteractions(claimService);
        worker.poll();

        verify(claimService).claim(1);
        verify(generationExecutor, never()).generate(any(), any());
    }

    @Test
    @DisplayName("선점 오류가 발생해도 다음 확인을 계속하고 오류 원문은 로그에 쓰지 않는다")
    void continueAfterClaimFailureWithoutLoggingSensitiveMessage(CapturedOutput output) {
        when(claimService.claim(1))
                .thenThrow(new IllegalStateException("private-diary-input-in-error"))
                .thenReturn(List.of());
        DiaryGenerationWorker worker = worker(Runnable::run, 1);

        worker.poll();
        worker.poll();

        verify(claimService, times(2)).claim(1);
        verifyNoInteractions(completionService);
        assertThat(output).contains("event=generation_worker_claim_failed")
                .doesNotContain("private-diary-input-in-error");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("생성 기록이나 일기 조회의 일시적인 DB 오류는 한 번 재조회하고 AI를 한 번 실행한다")
    void retryTransientInputReadOnceBeforeGenerating(boolean failOnGenerationRead, CapturedOutput output) {
        Fixture fixture = processingGeneration();
        stubInput(fixture);
        var exception = new TransientDataAccessResourceException("private-diary-input-in-error");
        if (failOnGenerationRead) {
            when(generationRepository.findById(fixture.generation().getId()))
                    .thenThrow(exception).thenReturn(Optional.of(fixture.generation()));
        } else {
            when(diaryRepository.findById(fixture.diary().getId()))
                    .thenThrow(exception).thenReturn(Optional.of(fixture.diary()));
        }
        when(claimService.claim(1)).thenReturn(List.of(fixture.generation().getId()));

        worker(Runnable::run, 1).poll();

        verify(claimService).claim(1);
        verify(generationRepository, times(2)).findById(fixture.generation().getId());
        verify(diaryRepository, times(failOnGenerationRead ? 1 : 2)).findById(fixture.diary().getId());
        Diary diary = fixture.diary();
        verify(generationExecutor).generate(new GenerateDiaryImageCommand(
                diary.getUserId(), diary.getId(), diary.getDiaryDate(), diary.getSourceText(), fixture.requestKey()
        ), fixture.generation().getId());
        verifyNoInteractions(completionService);
        assertThat(output).contains("event=generation_worker_input_read_retry")
                .contains("generationId=" + fixture.generation().getId())
                .doesNotContain("private-diary-input-in-error");
    }

    @Test
    @DisplayName("두 번 모두 입력 조회에 실패하면 최종 실패를 저장하고 다음 작업을 실행한다")
    void failAfterSecondTransientInputReadAndReleaseSlot() {
        Fixture fixture = processingGeneration();
        Fixture next = processingGeneration();
        when(generationRepository.findById(fixture.generation().getId()))
                .thenReturn(Optional.of(fixture.generation()));
        when(diaryRepository.findById(fixture.diary().getId()))
                .thenThrow(new TransientDataAccessResourceException("첫 조회 실패"))
                .thenThrow(new TransientDataAccessResourceException("재조회 실패"));
        stubInput(next);
        when(claimService.claim(1)).thenReturn(List.of(fixture.generation().getId()))
                .thenReturn(List.of(next.generation().getId()));
        DiaryGenerationWorker worker = worker(Runnable::run, 1);

        worker.poll();
        worker.poll();

        verify(diaryRepository, times(2)).findById(fixture.diary().getId());
        verify(completionService).fail(fixture.generation().getId(), GenerationErrorCode.GENERATION_INTERNAL_ERROR);
        verify(generationExecutor, never()).generate(any(), eq(fixture.generation().getId()));
        verify(generationExecutor).generate(any(), eq(next.generation().getId()));
    }

    @Test
    @DisplayName("생성 기록과 일기 조회가 차례로 실패해도 입력 조회 전체에서 한 번만 재시도한다")
    void shareSingleRetryAcrossGenerationAndDiaryReads() {
        Fixture fixture = processingGeneration();
        when(generationRepository.findById(fixture.generation().getId()))
                .thenThrow(new TransientDataAccessResourceException("생성 기록 조회 실패"))
                .thenReturn(Optional.of(fixture.generation()));
        when(diaryRepository.findById(fixture.diary().getId()))
                .thenThrow(new TransientDataAccessResourceException("일기 조회 실패"));
        when(claimService.claim(1)).thenReturn(List.of(fixture.generation().getId()));

        worker(Runnable::run, 1).poll();

        verify(diaryRepository).findById(fixture.diary().getId());
        verify(generationExecutor, never()).generate(any(), any());
        verify(completionService).fail(fixture.generation().getId(), GenerationErrorCode.GENERATION_INTERNAL_ERROR);
    }

    @Test
    @DisplayName("일시적인 오류로 분류하지 않은 DB 조회 오류는 재조회하지 않는다")
    void doNotRetryNonTransientInputReadFailure() {
        Fixture fixture = processingGeneration();
        when(generationRepository.findById(fixture.generation().getId()))
                .thenReturn(Optional.of(fixture.generation()));
        when(diaryRepository.findById(fixture.diary().getId()))
                .thenThrow(new DataAccessResourceFailureException("DB 조회 실패"));
        when(claimService.claim(1)).thenReturn(List.of(fixture.generation().getId()));

        worker(Runnable::run, 1).poll();

        verify(diaryRepository).findById(fixture.diary().getId());
        verify(generationExecutor, never()).generate(any(), any());
        verify(completionService).fail(fixture.generation().getId(), GenerationErrorCode.GENERATION_INTERNAL_ERROR);
    }

    @Test
    @DisplayName("AI 실행 중 일시적인 DB 오류가 나도 입력 조회와 AI 실행을 반복하지 않는다")
    void doNotRetryExecutorOnTransientDatabaseFailure(CapturedOutput output) {
        Fixture fixture = processingGeneration();
        stubInput(fixture);
        when(claimService.claim(1)).thenReturn(List.of(fixture.generation().getId()));
        when(generationExecutor.generate(any(), eq(fixture.generation().getId())))
                .thenThrow(new TransientDataAccessResourceException("생성 결과 저장 실패"));

        worker(Runnable::run, 1).poll();

        verify(diaryRepository).findById(fixture.diary().getId());
        verify(generationExecutor).generate(any(), eq(fixture.generation().getId()));
        verify(completionService).fail(fixture.generation().getId(), GenerationErrorCode.GENERATION_INTERNAL_ERROR);
        assertThat(output).doesNotContain("event=generation_worker_input_read_retry");
    }

    @Test
    @DisplayName("재조회 시 이미 종료된 작업이면 AI를 실행하거나 실패 상태를 덮어쓰지 않는다")
    void skipGenerationFinalizedBeforeInputRetry() {
        Fixture fixture = processingGeneration();
        when(generationRepository.findById(fixture.generation().getId()))
                .thenReturn(Optional.of(fixture.generation()));
        when(diaryRepository.findById(fixture.diary().getId())).thenAnswer(invocation -> {
            fixture.generation().fail(GenerationErrorCode.GENERATION_INTERRUPTED, Instant.now());
            throw new TransientDataAccessResourceException("일기 조회 실패");
        });
        when(claimService.claim(1)).thenReturn(List.of(fixture.generation().getId()));

        worker(Runnable::run, 1).poll();

        verify(generationRepository, times(2)).findById(fixture.generation().getId());
        verify(diaryRepository).findById(fixture.diary().getId());
        verify(generationExecutor, never()).generate(any(), any());
        verifyNoInteractions(completionService);
    }

    @Test
    @DisplayName("입력을 읽지 못하면 실패를 저장하고 다음 생성 작업을 실행한다")
    void persistInputFailureAndContinueWithNextGeneration() {
        Fixture missingInput = processingGeneration();
        Fixture next = processingGeneration();
        when(generationRepository.findById(missingInput.generation().getId()))
                .thenReturn(Optional.of(missingInput.generation()));
        when(diaryRepository.findById(missingInput.diary().getId())).thenReturn(Optional.empty());
        stubInput(next);
        when(claimService.claim(1)).thenReturn(List.of(missingInput.generation().getId()))
                .thenReturn(List.of(next.generation().getId()));
        DiaryGenerationWorker worker = worker(Runnable::run, 1);

        worker.poll();
        worker.poll();

        verify(completionService).fail(missingInput.generation().getId(), GenerationErrorCode.GENERATION_INTERNAL_ERROR);
        verify(diaryRepository).findById(missingInput.diary().getId());
        verify(generationExecutor, never()).generate(any(), eq(missingInput.generation().getId()));
        verify(generationExecutor).generate(any(), eq(next.generation().getId()));
    }

    @Test
    @DisplayName("삭제된 일기는 외부 생성을 실행하지 않고 실패를 저장한다")
    void rejectDeletedDiary() {
        Fixture fixture = processingGeneration();
        fixture.diary().delete(Instant.now());
        stubInput(fixture);
        when(claimService.claim(1)).thenReturn(List.of(fixture.generation().getId()));

        worker(Runnable::run, 1).poll();

        verify(diaryRepository).findById(fixture.diary().getId());
        verify(generationExecutor, never()).generate(any(), any());
        verify(completionService).fail(fixture.generation().getId(), GenerationErrorCode.GENERATION_INTERNAL_ERROR);
    }

    @Test
    @DisplayName("실행 전에 이미 종료된 작업은 생성하지 않는다")
    void skipGenerationAlreadyFinalizedBeforeInputLoad() {
        Fixture fixture = processingGeneration();
        fixture.generation().fail(GenerationErrorCode.GENERATION_INTERRUPTED, Instant.now());
        when(generationRepository.findById(fixture.generation().getId())).thenReturn(Optional.of(fixture.generation()));
        when(claimService.claim(1)).thenReturn(List.of(fixture.generation().getId()));

        worker(Runnable::run, 1).poll();

        verify(generationExecutor, never()).generate(any(), any());
        verifyNoInteractions(diaryRepository, completionService);
    }

    @Test
    @DisplayName("기존 실행기가 저장한 실패와 오류 코드를 덮어쓰지 않는다")
    void preserveFailureAlreadyPersistedByExecutor() {
        Fixture fixture = processingGeneration();
        stubInput(fixture);
        when(claimService.claim(1)).thenReturn(List.of(fixture.generation().getId()));
        when(generationExecutor.generate(any(), eq(fixture.generation().getId()))).thenAnswer(invocation -> {
            fixture.generation().fail(GenerationErrorCode.AI_PROVIDER_TIMEOUT, Instant.now());
            throw new IllegalStateException("외부 생성 오류");
        });

        worker(Runnable::run, 1).poll();

        assertThat(fixture.generation().getErrorCode()).isEqualTo(GenerationErrorCode.AI_PROVIDER_TIMEOUT);
        verifyNoInteractions(completionService);
    }

    @Test
    @DisplayName("성공 저장 뒤 오류가 전달되어도 성공 기록을 실패로 바꾸지 않는다")
    void preserveSuccessWhenExecutorThrowsAfterCompletion() {
        Fixture fixture = processingGeneration();
        stubInput(fixture);
        when(claimService.claim(1)).thenReturn(List.of(fixture.generation().getId()));
        when(generationExecutor.generate(any(), eq(fixture.generation().getId()))).thenAnswer(invocation -> {
            ReflectionTestUtils.setField(fixture.generation(), "status", GenerationStatus.SUCCEEDED);
            throw new IllegalStateException("성공 저장 후 응답 오류");
        });

        worker(Runnable::run, 1).poll();

        assertThat(fixture.generation().getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        verifyNoInteractions(completionService);
    }

    @Test
    @DisplayName("실패 저장 중 오류가 나도 실행 자리를 반환하고 다음 확인을 계속한다")
    void continueAfterFailurePersistenceError(CapturedOutput output) {
        Fixture fixture = processingGeneration();
        when(generationRepository.findById(fixture.generation().getId())).thenReturn(Optional.of(fixture.generation()));
        when(diaryRepository.findById(fixture.diary().getId())).thenReturn(Optional.empty());
        when(completionService.fail(fixture.generation().getId(), GenerationErrorCode.GENERATION_INTERNAL_ERROR))
                .thenThrow(new IllegalStateException("private-storage-error"));
        when(claimService.claim(1)).thenReturn(List.of(fixture.generation().getId())).thenReturn(List.of());
        DiaryGenerationWorker worker = worker(Runnable::run, 1);

        worker.poll();
        worker.poll();

        verify(claimService, times(2)).claim(1);
        assertThat(output).contains("event=generation_worker_failure_persistence_failed")
                .doesNotContain("private-storage-error");
    }

    @Test
    @DisplayName("종료가 시작되면 예약된 실행도 새 작업을 선점하지 않는다")
    void stopQueuedAndFutureClaims() {
        Queue<Runnable> tasks = new ArrayDeque<>();
        DiaryGenerationWorker worker = worker(tasks::add, 1);
        worker.poll();

        worker.requestStop();
        tasks.remove().run();
        worker.poll();

        assertThat(tasks).isEmpty();
        verifyNoInteractions(claimService, generationRepository, diaryRepository, completionService);
    }

    @Test
    @DisplayName("실제 AI와 Stub을 합해 설정한 개수까지만 병렬 실행하고 종료 후에는 선점을 멈춘다")
    void limitConcurrentExecutionAndFinishClaimedWorkOnStop() throws Exception {
        Fixture first = processingGeneration();
        Fixture second = processingGeneration();
        stubInput(first);
        stubInput(second);
        when(claimService.claim(1)).thenReturn(List.of(first.generation().getId()))
                .thenReturn(List.of(second.generation().getId()));
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        DiaryGenerationExecutor stub = mock(DiaryGenerationExecutor.class);
        when(stub.isConfigured()).thenReturn(true);
        LoadTestGenerationRegistry registry = mock(LoadTestGenerationRegistry.class);
        when(registry.findExecutor(second.generation().getId())).thenReturn(Optional.of(stub));
        Runnable awaitRelease = () -> {
            maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            started.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            } finally {
                active.decrementAndGet();
                finished.countDown();
            }
        };
        when(generationExecutor.generate(any(), any())).thenAnswer(invocation -> {
            awaitRelease.run();
            return null;
        });
        when(stub.generate(any(), any())).thenAnswer(invocation -> {
            awaitRelease.run();
            return null;
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            DiaryGenerationWorker worker = new DiaryGenerationWorker(
                    claimService, generationRepository, diaryRepository, generationExecutor, Optional.of(registry),
                    completionService, executor::execute, new GenerationWorkerProperties(2, Duration.ofSeconds(5))
            );
            try {
                worker.poll();
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                worker.poll();
                verify(claimService, times(2)).claim(1);
                assertThat(maximumActive.get()).isEqualTo(2);
                worker.requestStop();
                worker.poll();
            } finally {
                release.countDown();
            }
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        }
        verify(claimService, times(2)).claim(1);
        verify(generationExecutor).generate(any(), eq(first.generation().getId()));
        verify(stub).generate(any(), eq(second.generation().getId()));
        verify(registry).remove(first.generation().getId());
        verify(registry).remove(second.generation().getId());
        verifyNoInteractions(completionService);
    }

    @Test
    @DisplayName("선점한 테스트 작업은 가짜 AI로 실행하고 일반 실행기는 호출하지 않는다")
    void routeClaimedLoadTestGenerationToStub() {
        Fixture fixture = processingGeneration();
        stubInput(fixture);
        DiaryGenerationExecutor stub = mock(DiaryGenerationExecutor.class);
        when(stub.isConfigured()).thenReturn(true);
        when(claimService.claim(1)).thenReturn(List.of(fixture.generation().getId()));
        DiaryGenerationWorker worker = workerWithStub(stub, fixture.generation().getId());

        worker.poll();

        verify(stub).generate(any(), eq(fixture.generation().getId()));
        verify(generationExecutor, never()).generate(any(), any());
    }

    @Test
    @DisplayName("가짜 AI가 함께 있어도 일반 작업은 실제 실행기로 처리한다")
    void keepRealExecutorForNormalGeneration() {
        Fixture fixture = processingGeneration();
        stubInput(fixture);
        DiaryGenerationExecutor stub = mock(DiaryGenerationExecutor.class);
        when(stub.isConfigured()).thenReturn(true);
        when(claimService.claim(1)).thenReturn(List.of(fixture.generation().getId()));

        workerWithStub(stub, UUID.randomUUID()).poll();

        verify(generationExecutor).generate(any(), eq(fixture.generation().getId()));
        verify(stub, never()).generate(any(), any());
    }

    @Test
    @DisplayName("실제 AI가 없어도 가짜 AI가 준비되면 등록된 테스트 작업을 실행한다")
    void runRegisteredStubWhenRealExecutorUnavailable() {
        when(generationExecutor.isConfigured()).thenReturn(false);
        Fixture fixture = processingGeneration();
        stubInput(fixture);
        DiaryGenerationExecutor stub = mock(DiaryGenerationExecutor.class);
        when(stub.isConfigured()).thenReturn(true);
        when(claimService.claim(1)).thenReturn(List.of(fixture.generation().getId()));

        workerWithStub(stub, fixture.generation().getId()).poll();

        verify(stub).generate(any(), eq(fixture.generation().getId()));
        verify(generationExecutor, never()).generate(any(), any());
    }

    @Test
    @DisplayName("등록된 테스트 실행기가 준비되지 않으면 실제 AI로 대신 실행하지 않는다")
    void neverFallBackToRealAiForRegisteredLoadTest() {
        Fixture fixture = processingGeneration();
        stubInput(fixture);
        DiaryGenerationExecutor stub = mock(DiaryGenerationExecutor.class);
        when(claimService.claim(1)).thenReturn(List.of(fixture.generation().getId()));

        workerWithStub(stub, fixture.generation().getId()).poll();

        verify(generationExecutor, never()).generate(any(), any());
        verify(stub, never()).generate(any(), any());
        verify(completionService).fail(fixture.generation().getId(), GenerationErrorCode.GENERATION_INTERNAL_ERROR);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Stub 실행이 성공하거나 실패하면 메모리 연결을 지운다")
    void removeRegistrationAfterExecution(boolean fail) {
        Fixture fixture = processingGeneration();
        stubInput(fixture);
        DiaryGenerationExecutor stub = mock(DiaryGenerationExecutor.class);
        when(stub.isConfigured()).thenReturn(true);
        LoadTestGenerationRegistry registry = mock(LoadTestGenerationRegistry.class);
        when(registry.findExecutor(fixture.generation().getId())).thenReturn(Optional.of(stub));
        when(claimService.claim(1)).thenReturn(List.of(fixture.generation().getId()));
        if (fail) {
            when(stub.generate(any(), any())).thenThrow(new IllegalStateException("테스트 생성 실패"));
        }

        workerWithRegistry(registry).poll();

        verify(registry).remove(fixture.generation().getId());
        verify(generationExecutor, never()).generate(any(), any());
    }

    @Test
    @DisplayName("실행 전에 종료된 테스트 작업도 메모리 연결을 지운다")
    void removeRegistrationWhenGenerationAlreadyFinalized() {
        Fixture fixture = processingGeneration();
        fixture.generation().fail(GenerationErrorCode.GENERATION_INTERRUPTED, Instant.now());
        when(generationRepository.findById(fixture.generation().getId())).thenReturn(Optional.of(fixture.generation()));
        when(claimService.claim(1)).thenReturn(List.of(fixture.generation().getId()));
        LoadTestGenerationRegistry registry = mock(LoadTestGenerationRegistry.class);

        workerWithRegistry(registry).poll();

        verify(registry).remove(fixture.generation().getId());
        verify(generationExecutor, never()).generate(any(), any());
    }

    private DiaryGenerationWorker workerWithStub(DiaryGenerationExecutor stub, UUID registeredId) {
        LoadTestGenerationRegistry registry = mock(LoadTestGenerationRegistry.class);
        when(registry.isConfigured()).thenAnswer(invocation -> stub.isConfigured());
        when(registry.findExecutor(any())).thenAnswer(invocation ->
                registeredId.equals(invocation.getArgument(0)) ? Optional.of(stub) : Optional.empty());
        return workerWithRegistry(registry);
    }

    private DiaryGenerationWorker workerWithRegistry(LoadTestGenerationRegistry registry) {
        return new DiaryGenerationWorker(claimService, generationRepository, diaryRepository, generationExecutor,
                Optional.of(registry), completionService, Runnable::run,
                new GenerationWorkerProperties(1, Duration.ofSeconds(5)));
    }

    private DiaryGenerationWorker worker(TaskExecutor taskExecutor, int concurrency) {
        return new DiaryGenerationWorker(claimService, generationRepository, diaryRepository, generationExecutor, Optional.empty(),
                completionService, taskExecutor, new GenerationWorkerProperties(concurrency, Duration.ofSeconds(5)));
    }

    private Fixture processingGeneration() {
        Diary diary = Diary.create(UUID.randomUUID(), LocalDate.of(2026, 10, 20), "DB에 저장된 생성 입력");
        UUID requestKey = UUID.randomUUID();
        GenerateDiaryImageCommand command = new GenerateDiaryImageCommand(
                diary.getUserId(), diary.getId(), diary.getDiaryDate(), diary.getSourceText(), requestKey
        );
        String fingerprint = new RequestFingerprintGenerator().generate(command);
        DiaryGeneration generation = DiaryGeneration.pending(diary.getId(), 1L, requestKey, fingerprint, null);
        generation.startProcessing();
        return new Fixture(generation, diary, requestKey);
    }

    private void stubInput(Fixture fixture) {
        when(generationRepository.findById(fixture.generation().getId())).thenReturn(Optional.of(fixture.generation()));
        when(diaryRepository.findById(fixture.diary().getId())).thenReturn(Optional.of(fixture.diary()));
    }

    private record Fixture(DiaryGeneration generation, Diary diary, UUID requestKey) {
    }
}
