package com.harudle.generation.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.support.ServiceIntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PendingDiaryGenerationClaimPersistenceTest extends ServiceIntegrationTestSupport {

    private static final Instant CREATED_AT = Instant.parse("2026-08-06T00:00:00Z");
    private static final UUID FIRST_ID = new UUID(0, 1);
    private static final UUID SECOND_ID = new UUID(0, 2);
    private static final UUID THIRD_ID = new UUID(0, 3);
    private static final long TIMEOUT_SECONDS = 5;

    @Autowired
    private PendingDiaryGenerationClaimService claimService;

    @Autowired
    private DiaryGenerationRepository diaryGenerationRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private GenerationQueueMetrics queueMetrics;

    private UUID userId;
    private Long promptId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, name) VALUES (?, '작업 선점 테스트')", userId);
        promptId = jdbcTemplate.queryForObject("""
                INSERT INTO generation_prompts (
                    storyboard_prompt_text, image_style_prompt_text, image_asset_object_key
                )
                VALUES ('스토리보드 프롬프트', '이미지 스타일 프롬프트', 'references/claim.png')
                RETURNING id
                """, Long.class);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        jdbcTemplate.update("DELETE FROM generation_prompts WHERE id = ?", promptId);
    }

    @Test
    @DisplayName("대기 작업만 생성 시각과 ID 순서로 정해진 개수만큼 가져온다")
    void claimPendingGenerationsInOrderWithinLimit() {
        long initialWaitCount = waitCount();
        insertGeneration(THIRD_ID, GenerationStatus.PENDING, CREATED_AT.plusSeconds(1));
        insertGeneration(SECOND_ID, GenerationStatus.PENDING, CREATED_AT);
        insertGeneration(FIRST_ID, GenerationStatus.PENDING, CREATED_AT);
        UUID processingId = UUID.randomUUID();
        UUID succeededId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        insertGeneration(processingId, GenerationStatus.PROCESSING, CREATED_AT.minusSeconds(1));
        insertGeneration(succeededId, GenerationStatus.SUCCEEDED, CREATED_AT.minusSeconds(1));
        insertGeneration(failedId, GenerationStatus.FAILED, CREATED_AT.minusSeconds(1));

        assertThat(claimService.claim(2)).containsExactly(FIRST_ID, SECOND_ID);
        assertThat(waitCount()).isEqualTo(initialWaitCount + 2);

        assertThat(findStatus(FIRST_ID)).isEqualTo(GenerationStatus.PROCESSING);
        assertThat(findStatus(SECOND_ID)).isEqualTo(GenerationStatus.PROCESSING);
        assertThat(findStatus(THIRD_ID)).isEqualTo(GenerationStatus.PENDING);
        assertThat(findStatus(processingId)).isEqualTo(GenerationStatus.PROCESSING);
        assertThat(findStatus(succeededId)).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(findStatus(failedId)).isEqualTo(GenerationStatus.FAILED);
        assertThat(claimService.claim(2)).containsExactly(THIRD_ID);
        assertThat(claimService.claim(2)).isEmpty();
        assertThat(waitCount()).isEqualTo(initialWaitCount + 3);
    }

    @Test
    @DisplayName("대기 작업이 없으면 빈 목록을 반환한다")
    void returnEmptyWhenNoPendingGenerationExists() {
        assertThat(claimService.claim(1)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("가져올 개수가 1보다 작으면 대기 작업을 변경하지 않는다")
    void rejectInvalidBatchSize(int batchSize) {
        insertGeneration(FIRST_ID, GenerationStatus.PENDING, CREATED_AT);

        assertThatThrownBy(() -> claimService.claim(batchSize))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("가져올 생성 작업 수는 1 이상이어야 합니다.");

        assertThat(findStatus(FIRST_ID)).isEqualTo(GenerationStatus.PENDING);
    }

    @Test
    @DisplayName("오래 대기한 작업도 선점 시각부터 처리 제한 시간을 계산한다")
    void refreshProcessingTimeoutWhenClaimed() {
        insertGeneration(FIRST_ID, GenerationStatus.PENDING, CREATED_AT);
        Instant beforeClaim = Instant.now();

        assertThat(claimService.claim(1)).containsExactly(FIRST_ID);

        Timestamp updatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM diary_generations WHERE id = ?", Timestamp.class, FIRST_ID
        );
        assertThat(updatedAt.toInstant()).isAfterOrEqualTo(beforeClaim.truncatedTo(ChronoUnit.MICROS));
        DiaryGeneration generation = diaryGenerationRepository.findById(FIRST_ID).orElseThrow();
        assertThat(generation.interruptIfStale(Instant.now(), Duration.ofMinutes(15))).isFalse();
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PROCESSING);
    }

    @Test
    @DisplayName("다른 처리기가 잠근 작업을 기다리지 않고 다음 대기 작업을 가져온다")
    void skipLockedGenerationWithoutWaiting() throws Exception {
        insertGeneration(FIRST_ID, GenerationStatus.PENDING, CREATED_AT);
        insertGeneration(SECOND_ID, GenerationStatus.PENDING, CREATED_AT);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<UUID> firstClaim = executor.submit(() -> transactionTemplate.execute(status -> {
                DiaryGeneration generation = diaryGenerationRepository.findPendingForUpdateSkipLocked(1).getFirst();
                generation.startProcessing();
                diaryGenerationRepository.flush();
                firstLocked.countDown();
                await(releaseFirst);
                return generation.getId();
            }));
            try {
                assertThat(firstLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                Future<List<UUID>> secondClaim = executor.submit(() -> claimService.claim(1));

                assertThat(secondClaim.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).containsExactly(SECOND_ID);
                Future<List<UUID>> noUnlockedClaim = executor.submit(() -> claimService.claim(1));
                assertThat(noUnlockedClaim.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEmpty();
            } finally {
                releaseFirst.countDown();
            }
            assertThat(firstClaim.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(FIRST_ID);
        }
        assertThat(findStatus(FIRST_ID)).isEqualTo(GenerationStatus.PROCESSING);
        assertThat(findStatus(SECOND_ID)).isEqualTo(GenerationStatus.PROCESSING);
    }

    @Test
    @DisplayName("동시에 선점해도 대기 작업 하나는 한 처리기만 가져온다")
    void claimSingleGenerationOnlyOnceUnderConcurrency() throws Exception {
        insertGeneration(FIRST_ID, GenerationStatus.PENDING, CREATED_AT);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<List<UUID>>> claims = IntStream.range(0, 2)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        await(start);
                        return claimService.claim(1);
                    }))
                    .toList();
            try {
                assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            List<UUID> first = claims.getFirst().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<UUID> second = claims.getLast().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(Stream.concat(first.stream(), second.stream())).containsExactly(FIRST_ID);
        }
        assertThat(findStatus(FIRST_ID)).isEqualTo(GenerationStatus.PROCESSING);
    }

    @Test
    @DisplayName("상태 변경을 취소하면 잠금이 풀리고 같은 작업을 다시 가져올 수 있다")
    void reclaimGenerationAfterRollback() {
        insertGeneration(FIRST_ID, GenerationStatus.PENDING, CREATED_AT);
        long initialWaitCount = waitCount();

        transactionTemplate.executeWithoutResult(status -> {
            DiaryGeneration generation = diaryGenerationRepository.findPendingForUpdateSkipLocked(1).getFirst();
            generation.startProcessing();
            queueMetrics.claimedAfterCommit(generation.getCreatedAt(), Instant.now());
            diaryGenerationRepository.flush();
            status.setRollbackOnly();
        });

        assertThat(findStatus(FIRST_ID)).isEqualTo(GenerationStatus.PENDING);
        assertThat(waitCount()).isEqualTo(initialWaitCount);
        assertThat(claimService.claim(1)).containsExactly(FIRST_ID);
        assertThat(waitCount()).isEqualTo(initialWaitCount + 1);
    }

    @Test
    @DisplayName("선점 서비스가 반환되면 호출한 쪽의 트랜잭션이 남아 있어도 잠금은 풀린다")
    void commitClaimAndReleaseLockBeforeReturningToCaller() {
        insertGeneration(FIRST_ID, GenerationStatus.PENDING, CREATED_AT);

        try (var executor = Executors.newSingleThreadExecutor()) {
            transactionTemplate.executeWithoutResult(status -> {
                assertThat(claimService.claim(1)).containsExactly(FIRST_ID);
                Future<String> unlockedStatus = executor.submit(() -> transactionTemplate.execute(otherStatus ->
                        jdbcTemplate.queryForObject("""
                                SELECT status FROM diary_generations WHERE id = ? FOR UPDATE NOWAIT
                                """, String.class, FIRST_ID)
                ));
                try {
                    assertThat(unlockedStatus.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo("PROCESSING");
                } catch (Exception exception) {
                    throw new IllegalStateException("선점 서비스가 반환된 뒤에도 작업 잠금이 남아 있습니다.", exception);
                }
                status.setRollbackOnly();
            });
        }
        assertThat(findStatus(FIRST_ID)).isEqualTo(GenerationStatus.PROCESSING);
    }

    private long waitCount() {
        return meterRegistry.get("harudle.generation.queue.wait").timer().count();
    }

    private void insertGeneration(UUID generationId, GenerationStatus status, Instant createdAt) {
        UUID diaryId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO diaries (id, user_id, diary_date, source_text)
                VALUES (?, ?, DATE '2026-10-20', '작업 선점을 확인하는 일기')
                """, diaryId, userId);
        jdbcTemplate.update("""
                INSERT INTO diary_generations (
                    id, diary_id, prompt_id, idempotency_key, request_fingerprint, status,
                    image_object_key, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, generationId, diaryId, promptId, UUID.randomUUID(), "a".repeat(64), status.name(),
                status == GenerationStatus.SUCCEEDED ? "generated/" + generationId + ".png" : null,
                Timestamp.from(createdAt),
                Timestamp.from(status == GenerationStatus.PROCESSING ? Instant.now() : createdAt));
    }

    private GenerationStatus findStatus(UUID generationId) {
        return GenerationStatus.valueOf(jdbcTemplate.queryForObject(
                "SELECT status FROM diary_generations WHERE id = ?", String.class, generationId
        ));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS * 3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("선점 테스트의 대기 시간이 지났습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("선점 테스트 대기가 중단되었습니다.", exception);
        }
    }
}
