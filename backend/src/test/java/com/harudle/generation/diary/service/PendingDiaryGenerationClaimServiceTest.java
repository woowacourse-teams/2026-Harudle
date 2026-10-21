package com.harudle.generation.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.generation.config.GenerationLifecycleProperties;
import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

class PendingDiaryGenerationClaimServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final Duration PENDING_TIMEOUT = Duration.ofSeconds(120);
    private static final Instant CREATED_AFTER = NOW.minus(PENDING_TIMEOUT);
    private static final GenerationLifecycleProperties LIFECYCLE_PROPERTIES = new GenerationLifecycleProperties(
            Duration.ofMinutes(30), Duration.ofMinutes(1), PENDING_TIMEOUT, Duration.ofSeconds(5)
    );

    private final DiaryGenerationRepository repository = mock(DiaryGenerationRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private GenerationQueueMetrics metrics;
    private PendingDiaryGenerationClaimService service;
    private TransactionTemplate transaction;
    private Connection connection;

    @BeforeEach
    void setUp() throws SQLException {
        metrics = new GenerationQueueMetrics(registry, repository);
        service = new PendingDiaryGenerationClaimService(
                repository, metrics, LIFECYCLE_PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC)
        );
        DataSource dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    @DisplayName("선점 저장이 끝난 뒤에만 각 작업의 대기 시간을 한 번 기록한다")
    void recordWaitForEachClaimOnlyAfterCommit() {
        DiaryGeneration first = pendingAt(NOW.minusSeconds(2));
        DiaryGeneration second = pendingAt(NOW.minusSeconds(5));
        when(repository.findPendingForUpdateSkipLocked(CREATED_AFTER, 2)).thenReturn(List.of(first, second));

        List<UUID> claimed = transaction.execute(status -> {
            List<UUID> result = service.claim(2);
            assertThat(waitTimer().count()).isZero();
            return result;
        });

        assertThat(claimed).containsExactly(first.getId(), second.getId());
        assertThat(waitTimer().count()).isEqualTo(2);
        assertThat(waitTimer().totalTime(TimeUnit.SECONDS)).isEqualTo(7);
    }

    @Test
    @DisplayName("선점 저장을 취소하면 대기 시간을 기록하지 않는다")
    void doNotRecordWaitOnRollback() {
        when(repository.findPendingForUpdateSkipLocked(CREATED_AFTER, 1)).thenReturn(List.of(pendingAt(NOW.minusSeconds(5))));

        transaction.executeWithoutResult(status -> {
            service.claim(1);
            status.setRollbackOnly();
        });

        assertThat(waitTimer().count()).isZero();
    }

    @Test
    @DisplayName("DB 커밋이 실패하면 대기 시간을 기록하지 않는다")
    void doNotRecordWaitWhenCommitFails() throws SQLException {
        when(repository.findPendingForUpdateSkipLocked(CREATED_AFTER, 1)).thenReturn(List.of(pendingAt(NOW.minusSeconds(5))));
        doThrow(new SQLException("테스트 커밋 실패")).when(connection).commit();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.claim(1)))
                .isInstanceOf(TransactionSystemException.class);

        assertThat(waitTimer().count()).isZero();
    }

    @Test
    @DisplayName("대기 작업이 없으면 대기 시간 집계를 늘리지 않는다")
    void doNotRecordEmptyClaim() {
        when(repository.findPendingForUpdateSkipLocked(CREATED_AFTER, 1)).thenReturn(List.of());

        List<UUID> claimed = transaction.execute(status -> service.claim(1));

        assertThat(claimed).isEmpty();
        assertThat(waitTimer().count()).isZero();
    }

    @Test
    @DisplayName("서버 시각 차이로 저장 시각이 미래이면 대기 시간을 0으로 기록한다")
    void clampFutureCreationTimeToZero() {
        when(repository.findPendingForUpdateSkipLocked(CREATED_AFTER, 1)).thenReturn(List.of(pendingAt(NOW.plusSeconds(1))));

        transaction.executeWithoutResult(status -> service.claim(1));

        assertThat(waitTimer().count()).isEqualTo(1);
        assertThat(waitTimer().totalTime(TimeUnit.SECONDS)).isZero();
    }

    @Test
    @DisplayName("대기 시간 기록이 실패해도 저장한 선점 ID를 정상 반환한다")
    void keepCommittedClaimWhenMetricsRecordingFails() {
        Timer timer = mock(Timer.class);
        doThrow(new IllegalStateException("테스트 지표 오류")).when(timer).record(any(Duration.class));
        ReflectionTestUtils.setField(metrics, "queueWait", timer);
        DiaryGeneration generation = pendingAt(NOW.minusSeconds(5));
        when(repository.findPendingForUpdateSkipLocked(CREATED_AFTER, 1)).thenReturn(List.of(generation));

        List<UUID> claimed = transaction.execute(status -> service.claim(1));

        assertThat(claimed).containsExactly(generation.getId());
    }

    @Test
    @DisplayName("잠금을 잡은 뒤 기한이 지난 작업은 대기로 두고 남은 작업만 선점한다")
    void excludeExpiredCandidatesAfterLock() {
        DiaryGeneration expired = pendingAt(CREATED_AFTER.minusSeconds(1));
        DiaryGeneration atDeadline = pendingAt(CREATED_AFTER);
        DiaryGeneration recent = pendingAt(CREATED_AFTER.plusSeconds(1));
        when(repository.findPendingForUpdateSkipLocked(CREATED_AFTER, 3))
                .thenReturn(List.of(expired, atDeadline, recent));

        List<UUID> claimed = transaction.execute(status -> service.claim(3));

        assertThat(claimed).containsExactly(recent.getId());
        assertThat(expired.getStatus()).isEqualTo(GenerationStatus.PENDING);
        assertThat(atDeadline.getStatus()).isEqualTo(GenerationStatus.PENDING);
        assertThat(recent.getStatus()).isEqualTo(GenerationStatus.PROCESSING);
        assertThat(waitTimer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("조회 중에 대기 기한이 지나면 선점하지 않고 만료 정리가 처리하도록 둔다")
    void recheckDeadlineAfterQuery() {
        Clock advancingClock = mock(Clock.class);
        when(advancingClock.instant()).thenReturn(NOW, NOW.plusSeconds(1));
        service = new PendingDiaryGenerationClaimService(repository, metrics, LIFECYCLE_PROPERTIES, advancingClock);
        DiaryGeneration generation = pendingAt(CREATED_AFTER.plusSeconds(1));
        when(repository.findPendingForUpdateSkipLocked(CREATED_AFTER, 1)).thenReturn(List.of(generation));

        List<UUID> claimed = transaction.execute(status -> service.claim(1));

        assertThat(claimed).isEmpty();
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PENDING);
        assertThat(waitTimer().count()).isZero();
    }

    @Test
    @DisplayName("설정한 대기 제한 시간으로 선점 가능한 저장 시각을 정한다")
    void useConfiguredPendingTimeout() {
        GenerationLifecycleProperties properties = new GenerationLifecycleProperties(
                Duration.ofMinutes(30), Duration.ofMinutes(1), Duration.ofSeconds(30), Duration.ofSeconds(5)
        );
        service = new PendingDiaryGenerationClaimService(repository, metrics, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        DiaryGeneration generation = pendingAt(NOW.minusSeconds(29));
        when(repository.findPendingForUpdateSkipLocked(NOW.minusSeconds(30), 1)).thenReturn(List.of(generation));

        List<UUID> claimed = transaction.execute(status -> service.claim(1));

        assertThat(claimed).containsExactly(generation.getId());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("잘못된 선점 개수는 DB 조회와 대기 시간 집계 전에 거절한다")
    void rejectInvalidBatchSize(int batchSize) {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.claim(batchSize)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(repository);
        assertThat(waitTimer().count()).isZero();
    }

    private Timer waitTimer() {
        return registry.get("harudle.generation.queue.wait").timer();
    }

    private DiaryGeneration pendingAt(Instant createdAt) {
        DiaryGeneration generation = DiaryGeneration.pending(UUID.randomUUID(), 1L, UUID.randomUUID(), "a".repeat(64), null);
        ReflectionTestUtils.setField(generation, "createdAt", createdAt);
        return generation;
    }
}
