package com.harudle.generation.diary.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.generation.diary.domain.GenerationErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class GenerationLifecycleMetricsTest {

    @Test
    @DisplayName("애플리케이션 시작 시 가능한 최종 상태와 예상 밖 오류 단계의 카운터를 0으로 등록한다")
    void registerBoundedCounterSeriesAtStartup() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new GenerationLifecycleMetrics(registry);

        for (GenerationLifecycleMetrics.Phase phase : GenerationLifecycleMetrics.Phase.values()) {
            assertThat(registry.get("harudle.generation.unexpected.failures")
                    .tag("phase", phase.name().toLowerCase(Locale.ROOT))
                    .counter()
                    .count()).isZero();
        }
        assertThat(registry.get("harudle.generation.finalizations")
                .tags("status", "SUCCEEDED", "errorCode", "none")
                .counter()
                .count()).isZero();
        for (GenerationErrorCode errorCode : GenerationErrorCode.values()) {
            assertThat(registry.get("harudle.generation.finalizations")
                    .tags("status", "FAILED", "errorCode", errorCode.name())
                    .counter()
                    .count()).isZero();
        }
        assertThat(registry.getMeters()).hasSize(
                GenerationLifecycleMetrics.Phase.values().length + 1 + GenerationErrorCode.values().length
        );
    }

    @Test
    @DisplayName("첫 Prometheus 수집에 0인 생성 실패 시계열을 노출한다")
    void exposeZeroCountersOnFirstPrometheusScrape() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new GenerationLifecycleMetrics(registry);

        String scrape = registry.scrape();

        assertThat(scrape).containsPattern(
                "(?m)^harudle_generation_unexpected_failures_total\\{phase=\"storyboard\"\\} 0\\.0$");
        assertThat(scrape).containsPattern(
                "(?m)^harudle_generation_finalizations_total\\{(?=[^\\n]*status=\"SUCCEEDED\")"
                        + "(?=[^\\n]*errorCode=\"none\")[^\\n]*\\} 0\\.0$");
    }

    @Test
    @DisplayName("예상 밖 생성 실패의 단계와 원인 종류만 기록하고 예외 메시지는 숨긴다")
    void recordUnexpectedFailureWithoutSensitiveMessage(CapturedOutput output) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GenerationLifecycleMetrics metrics = new GenerationLifecycleMetrics(registry);
        UUID generationId = UUID.randomUUID();
        IllegalStateException exception = new IllegalStateException("diary=must-not-be-logged");
        exception.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.harudle.generation.TestAdapter", "generate", "TestAdapter.java", 42)
        });

        metrics.unexpectedFailure(generationId, GenerationLifecycleMetrics.Phase.IMAGE_GENERATION, exception);

        assertThat(output)
                .contains("event=generation_unexpected_failure")
                .contains("generationId=" + generationId)
                .contains("phase=image_generation")
                .contains("exceptionType=IllegalStateException")
                .contains("at com.harudle.generation.TestAdapter.generate(TestAdapter.java:42)")
                .doesNotContain("must-not-be-logged");
        assertThat(registry.get("harudle.generation.unexpected.failures")
                .tag("phase", "image_generation")
                .counter()
                .count()).isEqualTo(1.0);
    }
}
