package com.harudle.generation.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(OutputCaptureExtension.class)
class GenerationQueueMetricsTest {

    private final DiaryGenerationRepository repository = mock(DiaryGenerationRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private GenerationQueueMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new GenerationQueueMetrics(registry, repository);
    }

    @Test
    @DisplayName("대기와 처리 중 작업 수는 수집 시점의 DB 값을 각각 반영한다")
    void observeCurrentPersistedCountsByStatus() {
        when(repository.countByStatusIn(List.of(GenerationStatus.PENDING))).thenReturn(2L, 0L);
        when(repository.countByStatusIn(List.of(GenerationStatus.PROCESSING))).thenReturn(1L, 3L);

        var pending = registry.get("harudle.generation.queue.size").tag("status", "PENDING").gauge();
        var processing = registry.get("harudle.generation.queue.size").tag("status", "PROCESSING").gauge();

        assertThat(pending.value()).isEqualTo(2);
        assertThat(processing.value()).isEqualTo(1);
        assertThat(pending.value()).isZero();
        assertThat(processing.value()).isEqualTo(3);
        assertThat(registry.getMeters()).hasSize(4);
    }

    @Test
    @DisplayName("DB 조회 오류는 작업 수 0으로 표시하지 않고 다음 수집에서 다시 조회한다")
    void reportUnavailableCountAndRecoverOnNextCollection(CapturedOutput output) {
        when(repository.countByStatusIn(List.of(GenerationStatus.PENDING)))
                .thenThrow(new DataAccessResourceFailureException("private-database-error"))
                .thenReturn(1L);
        var pending = registry.get("harudle.generation.queue.size").tag("status", "PENDING").gauge();

        assertThat(pending.value()).isNaN();
        assertThat(pending.value()).isEqualTo(1);
        assertThat(output).contains("component=generation_queue_size").doesNotContain("private-database-error");
    }

    @Test
    @DisplayName("접수 거절은 트랜잭션이 취소되어도 횟수를 한 번 기록한다")
    void retainRejectionCounterOnRollback() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transaction.executeWithoutResult(status -> {
            metrics.capacityRejected();
            status.setRollbackOnly();
        });

        assertThat(registry.get("harudle.generation.capacity.rejections").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("지표 기록 오류가 나도 접수 거절 처리를 방해하지 않고 오류 원문을 숨긴다")
    void ignoreCounterRecordingFailure(CapturedOutput output) {
        Counter counter = mock(Counter.class);
        doThrow(new IllegalStateException("private-metrics-error")).when(counter).increment();
        ReflectionTestUtils.setField(metrics, "capacityRejections", counter);

        assertThatCode(metrics::capacityRejected).doesNotThrowAnyException();

        assertThat(output).contains("component=generation_capacity_rejections").doesNotContain("private-metrics-error");
    }

    @Test
    @DisplayName("트랜잭션 밖에서 호출한 선점은 대기 시간에 집계하지 않는다")
    void doNotRecordClaimWithoutTransaction() {
        metrics.claimedAfterCommit(Instant.EPOCH, Instant.EPOCH.plusSeconds(5));

        assertThat(registry.get("harudle.generation.queue.wait").timer().count()).isZero();
    }

    @Test
    @DisplayName("Prometheus에 정해진 지표와 두 상태만 노출하고 사용자나 생성 ID는 붙이지 않는다")
    void exportBoundedPrometheusSeries() {
        var prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var queueMetrics = new GenerationQueueMetrics(prometheus, repository);
        queueMetrics.capacityRejected();

        String scrape = prometheus.scrape();

        assertThat(scrape)
                .containsPattern("(?m)^harudle_generation_queue_size\\{status=\"PENDING\"} 0\\.0$")
                .containsPattern("(?m)^harudle_generation_queue_size\\{status=\"PROCESSING\"} 0\\.0$")
                .contains("harudle_generation_queue_wait_seconds_count 0")
                .contains("harudle_generation_queue_wait_seconds_sum 0.0")
                .contains("harudle_generation_capacity_rejections_total 1.0")
                .doesNotContain("userId", "generationId", "traceId");
    }
}
