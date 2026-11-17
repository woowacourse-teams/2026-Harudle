package com.harudle.generation.adapter.out.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class OpenAiStageMetricsTest {

    @Test
    @DisplayName("스토리보드 총 시간과 최종 답변 시간 및 토큰을 이미지 지표와 다른 이름으로 기록한다")
    void recordStoryboardDurationUsageAndResponseTiming() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var metrics = new OpenAiStageMetrics(registry, "gpt-6-luna");
        var timing = new OpenAiChatClient.Timing(1, 22.0, 35);
        var response = new OpenAiChatClient.Completion("private content", null, "stop", timing);
        var usage = new GenerationTokenUsage(100, 390, 110, 600);

        metrics.record(null, TimeUnit.SECONDS.toNanos(36), response, usage);

        assertThat(registry.get("harudle.openai.stage.calls")
                .tags("stage", "storyboard", "model", "gpt-6-luna", "outcome", "success").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("harudle.openai.stage.duration").timer().totalTime(TimeUnit.SECONDS))
                .isEqualTo(36);
        assertThat(registry.get("harudle.openai.response.duration").tag("phase", "first_answer")
                .timer().totalTime(TimeUnit.SECONDS)).isEqualTo(22);
        assertThat(registry.get("harudle.openai.response.duration").tag("phase", "completed")
                .timer().totalTime(TimeUnit.SECONDS)).isEqualTo(35);
        assertThat(registry.get("harudle.openai.tokens").tag("kind", "candidate").counter().count())
                .isEqualTo(390);
        assertThat(registry.get("harudle.openai.tokens").tag("kind", "thought").counter().count()).isEqualTo(110);
        assertThat(registry.find("harudle.image.provider.duration").timer()).isNull();
    }

    @Test
    @DisplayName("실패 시간과 오류 종류를 기록하고 알 수 없는 토큰이나 수신 시각은 만들지 않는다")
    void recordFailureWithoutInventingMissingUsageOrTiming() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var metrics = new OpenAiStageMetrics(registry, "gpt-6-luna");

        metrics.record(AiGenerationErrorType.TIMEOUT, TimeUnit.SECONDS.toNanos(90), null,
                new GenerationTokenUsage(100, null, null, 600));

        assertThat(registry.get("harudle.openai.stage.calls")
                .tags("outcome", "failure", "failureType", "TIMEOUT").counter().count()).isEqualTo(1);
        assertThat(registry.get("harudle.openai.stage.duration").timer().totalTime(TimeUnit.SECONDS))
                .isEqualTo(90);
        assertThat(registry.find("harudle.openai.response.duration").timer()).isNull();
        assertThat(registry.find("harudle.openai.tokens").tag("kind", "candidate").counter()).isNull();
        assertThat(registry.find("harudle.openai.tokens").tag("kind", "thought").counter()).isNull();
        assertThat(registry.get("harudle.openai.tokens").tag("kind", "total").counter().count()).isEqualTo(600);
    }

    @Test
    @DisplayName("지표 태그에 넣기 어려운 모델 이름은 other로 바꾼다")
    void normalizeUnsafeModelTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new OpenAiStageMetrics(registry, "model with spaces").record(null, 1, null, null);

        assertThat(registry.get("harudle.openai.stage.calls").tag("model", "other").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("생성 완료 로그에는 지표 값만 남기고 일기와 응답 및 키 원문을 남기지 않는다")
    void logSafeStageMetadataWithoutResponseContent() {
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiStageMetrics.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new OpenAiStageMetrics(new SimpleMeterRegistry(), "gpt-6-luna").record(null, 1_000_000,
                    new OpenAiChatClient.Completion("private diary sk-proj-secret", null, "stop", null),
                    new GenerationTokenUsage(100, 390, 110, 600));
            ILoggingEvent event = appender.list.getFirst();
            assertThat(event.getFormattedMessage())
                    .contains("provider=openai", "stage=storyboard", "outcome=success");
            assertThat(event.getKeyValuePairs()).extracting(pair -> pair.key, pair -> pair.value)
                    .contains(tuple("promptTokenCount", 100), tuple("thoughtTokenCount", 110));
            assertThat(event.getKeyValuePairs().toString()).doesNotContain("private diary", "sk-proj-secret");
            assertThat(event.getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("지표 저장 실패가 생성 결과를 덮어쓰거나 원인 메시지를 노출하지 않는다")
    void keepMetricFailureIsolatedAndSanitized() {
        MeterRegistry registry = mock(MeterRegistry.class);
        when(registry.counter(anyString(), any(String[].class)))
                .thenThrow(new IllegalStateException("sk-proj-private-error"));
        var metrics = new OpenAiStageMetrics(registry, "gpt-6-luna");

        assertThatCode(() -> metrics.record(null, 10, null, null)).doesNotThrowAnyException();
    }
}
