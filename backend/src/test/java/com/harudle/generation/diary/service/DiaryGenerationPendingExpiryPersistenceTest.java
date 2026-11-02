package com.harudle.generation.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.support.ServiceIntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = "harudle.generation.lifecycle.pending-cleanup-interval=1h")
class DiaryGenerationPendingExpiryPersistenceTest extends ServiceIntegrationTestSupport {

    private static final Instant NOW = Instant.parse("2026-08-06T00:00:00Z");
    private static final Duration PENDING_TIMEOUT = Duration.ofSeconds(120);
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 6);
    private static final LocalDate USAGE_DATE = TODAY.minusDays(1);

    @Autowired
    private DiaryGenerationCompletionService completionService;

    @Autowired
    private DiaryGenerationRepository generationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    private UUID userId;
    private Long promptId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, name) VALUES (?, '대기 만료 테스트')", userId);
        promptId = jdbcTemplate.queryForObject("""
                INSERT INTO generation_prompts (
                    storyboard_prompt_text, image_style_prompt_text, image_asset_object_key
                )
                VALUES ('스토리보드 프롬프트', '이미지 스타일 프롬프트', 'references/pending-expiry.png')
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO daily_generation_usage (user_id, usage_date, used_count, limit_count)
                VALUES (?, ?, 2, 3), (?, ?, 2, 3)
                """, userId, USAGE_DATE, userId, TODAY);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        jdbcTemplate.update("DELETE FROM generation_prompts WHERE id = ?", promptId);
    }

    @Test
    @DisplayName("대기 만료와 일기 숨김을 저장하고 원래 날짜의 사용량을 한 번만 복구한다")
    void expireAndRefundOriginalUsageDateOnlyOnce() {
        PendingWork work = insertPendingGeneration();
        double before = finalizationCount();

        assertThat(expire(work)).isTrue();
        assertThat(expire(work)).isFalse();

        var generation = generationRepository.findById(work.generationId()).orElseThrow();
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(generation.getErrorCode()).isEqualTo(GenerationErrorCode.GENERATION_PENDING_TIMEOUT);
        assertThat(generation.getCompletedAt()).isEqualTo(NOW);
        assertThat(deletedAt(work)).isEqualTo(Timestamp.from(NOW));
        assertThat(usedCount(USAGE_DATE)).isEqualTo(1);
        assertThat(usedCount(TODAY)).isEqualTo(2);
        assertThat(finalizationCount()).isEqualTo(before + 1);

        var snapshot = generationRepository.findStatusSnapshotByIdAndUserId(work.generationId(), userId).orElseThrow();
        assertThat(snapshot.status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(snapshot.errorCode()).isEqualTo(GenerationErrorCode.GENERATION_PENDING_TIMEOUT);
        assertThat(generationRepository.findStatusSnapshotByIdAndUserId(work.generationId(), UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("사용량 복구가 실패하면 생성 상태와 일기 숨김도 함께 취소한다")
    void rollbackExpiryWhenUsageRestoreFails() {
        PendingWork work = insertPendingGeneration();
        double before = finalizationCount();
        String constraint = "pending_expiry_test_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("ALTER TABLE daily_generation_usage ADD CONSTRAINT " + constraint
                + " CHECK (user_id <> '" + userId + "'::uuid OR used_count = 2)");
        try {
            assertThatThrownBy(() -> expire(work)).isInstanceOf(DataAccessException.class);

            var generation = generationRepository.findById(work.generationId()).orElseThrow();
            assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PENDING);
            assertThat(generation.getErrorCode()).isNull();
            assertThat(generation.getCompletedAt()).isNull();
            assertThat(deletedAt(work)).isNull();
            assertThat(usedCount(USAGE_DATE)).isEqualTo(2);
            assertThat(usedCount(TODAY)).isEqualTo(2);
            assertThat(finalizationCount()).isEqualTo(before);
        } finally {
            jdbcTemplate.execute("ALTER TABLE daily_generation_usage DROP CONSTRAINT " + constraint);
        }
    }

    @Test
    @DisplayName("두 요청이 동시에 같은 작업을 만료해도 사용량을 한 번만 복구한다")
    void restoreUsageOnlyOnceUnderConcurrentExpiry() throws Exception {
        PendingWork work = insertPendingGeneration();
        double before = finalizationCount();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return expire(work);
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return expire(work);
            });
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(usedCount(USAGE_DATE)).isEqualTo(1);
        assertThat(finalizationCount()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("만료 처리 전에 선점된 작업은 대기 만료로 변경하지 않는다")
    void doNotExpireClaimedGeneration() {
        PendingWork work = insertPendingGeneration();
        double before = finalizationCount();
        transactionTemplate.executeWithoutResult(status -> generationRepository
                .findByIdForUpdate(work.generationId()).orElseThrow().startProcessing());

        assertThat(expire(work)).isFalse();

        assertThat(generationRepository.findById(work.generationId()).orElseThrow().getStatus())
                .isEqualTo(GenerationStatus.PROCESSING);
        assertThat(deletedAt(work)).isNull();
        assertThat(usedCount(USAGE_DATE)).isEqualTo(2);
        assertThat(finalizationCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("선점 저장과 만료가 겹치면 잠금 해제 뒤 처리 상태를 확인하고 만료를 취소한다")
    void keepProcessingWhenClaimCommitsDuringExpiry() throws Exception {
        PendingWork work = insertPendingGeneration();
        double before = finalizationCount();
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        CountDownLatch expiryStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var claim = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                generationRepository.findByIdForUpdate(work.generationId()).orElseThrow().startProcessing();
                generationRepository.flush();
                claimed.countDown();
                try {
                    assertThat(releaseClaim.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            try {
                assertThat(claimed.await(5, TimeUnit.SECONDS)).isTrue();
                var expiry = executor.submit(() -> {
                    expiryStarted.countDown();
                    return expire(work);
                });
                assertThat(expiryStarted.await(5, TimeUnit.SECONDS)).isTrue();
                releaseClaim.countDown();

                claim.get(5, TimeUnit.SECONDS);
                assertThat(expiry.get(5, TimeUnit.SECONDS)).isFalse();
            } finally {
                releaseClaim.countDown();
            }
        }

        assertThat(generationRepository.findById(work.generationId()).orElseThrow().getStatus())
                .isEqualTo(GenerationStatus.PROCESSING);
        assertThat(deletedAt(work)).isNull();
        assertThat(usedCount(USAGE_DATE)).isEqualTo(2);
        assertThat(finalizationCount()).isEqualTo(before);
    }

    private PendingWork insertPendingGeneration() {
        UUID diaryId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO diaries (id, user_id, diary_date, source_text)
                VALUES (?, ?, ?, '대기 만료를 확인하는 일기')
                """, diaryId, userId, USAGE_DATE);
        jdbcTemplate.update("""
                INSERT INTO diary_generations (
                    id, diary_id, prompt_id, idempotency_key, request_fingerprint, status,
                    created_at, updated_at, usage_date
                )
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
                """, generationId, diaryId, promptId, UUID.randomUUID(), "a".repeat(64),
                Timestamp.from(NOW.minus(PENDING_TIMEOUT)), Timestamp.from(NOW), USAGE_DATE);
        return new PendingWork(generationId, diaryId);
    }

    private boolean expire(PendingWork work) {
        return completionService.expirePendingIfStale(work.generationId(), NOW, PENDING_TIMEOUT);
    }

    private Timestamp deletedAt(PendingWork work) {
        return jdbcTemplate.queryForObject("SELECT deleted_at FROM diaries WHERE id = ?",
                Timestamp.class, work.diaryId());
    }

    private int usedCount(LocalDate date) {
        return jdbcTemplate.queryForObject("SELECT used_count FROM daily_generation_usage WHERE user_id = ? AND usage_date = ?",
                Integer.class, userId, date);
    }

    private double finalizationCount() {
        return meterRegistry.get("harudle.generation.finalizations")
                .tags("status", "FAILED", "errorCode", "GENERATION_PENDING_TIMEOUT").counter().count();
    }

    private record PendingWork(UUID generationId, UUID diaryId) {
    }
}
