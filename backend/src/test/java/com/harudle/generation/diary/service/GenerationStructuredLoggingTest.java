package com.harudle.generation.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.OutputStreamAppender;
import com.harudle.generation.config.StoryboardProviderProperties;
import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.domain.StoryboardProviderInfo;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import com.harudle.generation.diary.service.port.dto.GeneratedStoryboard;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import com.harudle.generation.diary.service.storyboard.StoryboardAttemptRunner;
import com.harudle.generation.diary.service.storyboard.StoryboardGenerationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GenerationStructuredLoggingTest {

    private static final UUID GENERATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String OBJECT_KEY = "generated/private-must-not-be-logged.png";
    private static final String FOLLOW_UP_MESSAGE = "following event remains valid JSON";
    private Map<String, String> originalMdc;

    @BeforeEach
    void preserveMdc() {
        originalMdc = MDC.getCopyOfContextMap();
        MDC.clear();
    }

    @AfterEach
    void restoreMdc() {
        MDC.clear();
        if (originalMdc != null) {
            MDC.setContextMap(originalMdc);
        }
    }

    @ParameterizedTest
    @EnumSource(GenerationContext.class)
    @DisplayName("커밋 뒤 최종화 로그는 요청 또는 스케줄러 문맥에서도 다음 로그와 함께 유효한 JSON이다")
    void finalizeAfterCommitWithoutDuplicateGenerationId(GenerationContext context) {
        context.apply();
        String previousGenerationId = MDC.get("generationId");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GenerationLifecycleMetrics metrics = new GenerationLifecycleMetrics(registry);
        try (StructuredLogCapture capture = new StructuredLogCapture()) {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                metrics.finalizedAfterCommit(GENERATION_ID, GenerationStatus.FAILED,
                        GenerationErrorCode.GENERATION_INTERRUPTED);
                TransactionSynchronizationManager.getSynchronizations().forEach(synchronization ->
                        synchronization.afterCommit());
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
                TransactionSynchronizationManager.clearSynchronization();
            }

            JsonNode event = capture.assertEventAndFollowUp("generation_finalized");
            assertThat(event.path("status").asString()).isEqualTo("FAILED");
            assertThat(event.path("errorCode").asString()).isEqualTo("GENERATION_INTERRUPTED");
            assertThat(registry.get("harudle.generation.finalizations")
                    .tags("status", "FAILED", "errorCode", "GENERATION_INTERRUPTED")
                    .counter().count()).isEqualTo(1.0);
        }
        assertThat(MDC.get("generationId")).isEqualTo(previousGenerationId);
    }

    @ParameterizedTest
    @EnumSource(GenerationContext.class)
    @DisplayName("예상 밖 생성 오류는 중복 키 없이 JSON과 안전한 예외 및 카운터를 기록한다")
    void recordUnexpectedFailureWithoutDuplicateGenerationId(GenerationContext context) {
        context.apply();
        String previousGenerationId = MDC.get("generationId");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GenerationLifecycleMetrics metrics = new GenerationLifecycleMetrics(registry);
        RuntimeException failure = new IllegalStateException(OBJECT_KEY);
        try (StructuredLogCapture capture = new StructuredLogCapture()) {
            metrics.unexpectedFailure(GENERATION_ID, GenerationLifecycleMetrics.Phase.IMAGE_GENERATION, failure);

            JsonNode event = capture.assertEventAndFollowUp("generation_unexpected_failure");
            assertThat(event.path("phase").asString()).isEqualTo("image_generation");
            assertThat(event.path("exceptionType").asString()).isEqualTo("IllegalStateException");
            assertThat(event.path("stack_trace").asString()).contains("Unexpected generation failure");
            assertThat(capture.contents()).doesNotContain(OBJECT_KEY);
            assertThat(registry.get("harudle.generation.unexpected.failures")
                    .tag("phase", "image_generation").counter().count()).isEqualTo(1.0);
        }
        assertThat(MDC.get("generationId")).isEqualTo(previousGenerationId);
    }

    @ParameterizedTest
    @EnumSource(GenerationContext.class)
    @DisplayName("폐기 이미지 삭제 완료 로그는 기존 MDC를 보존하며 유효한 JSON을 기록한다")
    void recordDiscardedImageDeletionWithoutDuplicateGenerationId(GenerationContext context) {
        context.apply();
        String previousGenerationId = MDC.get("generationId");
        ImageStorage storage = mock(ImageStorage.class);
        DiscardedGenerationImageCleaner cleaner = new DiscardedGenerationImageCleaner(
                mock(DiaryGenerationRepository.class), storage);
        try (StructuredLogCapture capture = new StructuredLogCapture()) {
            cleaner.deleteDiscardedImage(GENERATION_ID, OBJECT_KEY, "completion_failed");

            JsonNode event = capture.assertEventAndFollowUp("discarded_image_deleted");
            assertThat(event.path("reason").asString()).isEqualTo("completion_failed");
            assertThat(capture.contents()).doesNotContain(OBJECT_KEY);
        }
        verify(storage).delete(OBJECT_KEY);
        assertThat(MDC.get("generationId")).isEqualTo(previousGenerationId);
    }

    @ParameterizedTest
    @EnumSource(GenerationContext.class)
    @DisplayName("폐기 이미지 삭제 실패 로그는 중복 키나 민감한 예외 메시지 없이 기록한다")
    void recordDiscardedImageDeletionFailureWithoutDuplicateGenerationId(GenerationContext context) {
        context.apply();
        String previousGenerationId = MDC.get("generationId");
        ImageStorage storage = mock(ImageStorage.class);
        doThrow(new IllegalStateException(OBJECT_KEY)).when(storage).delete(OBJECT_KEY);
        DiscardedGenerationImageCleaner cleaner = new DiscardedGenerationImageCleaner(
                mock(DiaryGenerationRepository.class), storage);
        try (StructuredLogCapture capture = new StructuredLogCapture()) {
            cleaner.deleteDiscardedImage(GENERATION_ID, OBJECT_KEY, "completion_failed");

            JsonNode event = capture.assertEventAndFollowUp("discarded_image_delete_failed");
            assertThat(event.path("exceptionType").asString()).isEqualTo("IllegalStateException");
            assertThat(capture.contents()).doesNotContain(OBJECT_KEY);
        }
        assertThat(MDC.get("generationId")).isEqualTo(previousGenerationId);
    }

    @ParameterizedTest
    @EnumSource(GenerationContext.class)
    @DisplayName("삭제 안전성 확인 실패 로그는 중복 키 없이 기록하고 실제 삭제를 하지 않는다")
    void recordDeferredDeletionWithoutDuplicateGenerationId(GenerationContext context) {
        context.apply();
        String previousGenerationId = MDC.get("generationId");
        DiaryGenerationRepository repository = mock(DiaryGenerationRepository.class);
        when(repository.findById(GENERATION_ID)).thenThrow(new IllegalStateException(OBJECT_KEY));
        ImageStorage storage = mock(ImageStorage.class);
        DiscardedGenerationImageCleaner cleaner = new DiscardedGenerationImageCleaner(repository, storage);
        try (StructuredLogCapture capture = new StructuredLogCapture()) {
            cleaner.deleteIfSafelyDiscardable(
                    GENERATION_ID, OBJECT_KEY, new IllegalStateException("completion failed"));

            JsonNode event = capture.assertEventAndFollowUp("discarded_image_delete_deferred");
            assertThat(event.path("exceptionType").asString()).isEqualTo("IllegalStateException");
            assertThat(capture.contents()).doesNotContain(OBJECT_KEY);
        }
        verify(storage, never()).delete(OBJECT_KEY);
        assertThat(MDC.get("generationId")).isEqualTo(previousGenerationId);
    }

    private enum GenerationContext {
        NONE, CURRENT, OTHER;

        void apply() {
            if (this == CURRENT) {
                MDC.put("generationId", GENERATION_ID.toString());
            } else if (this == OTHER) {
                MDC.put("generationId", "outer-generation-context");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(GenerationContext.class)
    @DisplayName("메인 실패와 대체 사유 및 보조 성공을 같은 생성 ID와 실제 모델로 안전하게 기록한다")
    void recordProviderAttemptsAndFallbackWithoutSensitiveInput(GenerationContext context) {
        context.apply();
        String previousGenerationId = MDC.get("generationId");
        var info = new StoryboardProviderInfo("qwen", "qwen3.8-max");
        GeneratedStoryboard result = mock(GeneratedStoryboard.class);
        when(result.providerInfo()).thenReturn(info);
        StoryboardGenerator primary = provider(new StoryboardProviderInfo("gemini", "gemini-storyboard-model"), () -> {
            throw new AiGenerationException(AiGenerationErrorType.TIMEOUT, OBJECT_KEY);
        });
        StoryboardGenerator fallback = provider(info, () -> result);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                StructuredLogCapture capture = new StructuredLogCapture()) {
            var settings = new StoryboardProviderProperties("gemini", "qwen",
                    Duration.ofSeconds(60), Duration.ofSeconds(60));
            var service = new StoryboardGenerationService(primary, Optional.of(fallback),
                    new StoryboardAttemptRunner(workers), settings);
            GenerationLogContext.withGenerationId(GENERATION_ID,
                    () -> assertThat(service.generate(new StoryboardGenerationRequest(OBJECT_KEY, "private prompt")))
                            .isSameAs(result));

            var events = capture.assertEventsAndFollowUp("storyboard_attempt_completed",
                    "storyboard_fallback_triggered", "storyboard_attempt_completed");

            assertThat(events.get(0).path("provider").asString()).isEqualTo("gemini");
            assertThat(events.get(0).path("model").asString()).isEqualTo("gemini-storyboard-model");
            assertThat(events.get(0).path("attempt").asInt()).isEqualTo(1);
            assertThat(events.get(0).path("role").asString()).isEqualTo("primary");
            assertThat(events.get(0).path("status").asString()).isEqualTo("FAILED");
            assertThat(events.get(0).path("durationMs").asLong()).isGreaterThanOrEqualTo(0);
            assertThat(events.get(1).path("reason").asString()).isEqualTo("TIMEOUT");
            assertThat(events.get(1).path("primaryProvider").asString()).isEqualTo("gemini");
            assertThat(events.get(1).path("fallbackProvider").asString()).isEqualTo("qwen");
            assertThat(events.get(1).path("fallbackModel").asString()).isEqualTo("qwen3.8-max");
            assertThat(events.get(2).path("provider").asString()).isEqualTo("qwen");
            assertThat(events.get(2).path("model").asString()).isEqualTo("qwen3.8-max");
            assertThat(events.get(2).path("attempt").asInt()).isEqualTo(2);
            assertThat(events.get(2).path("role").asString()).isEqualTo("fallback");
            assertThat(events.get(2).path("status").asString()).isEqualTo("SUCCEEDED");
            assertThat(events.get(2).path("errorType").asString()).isEqualTo("none");
            assertThat(capture.contents()).doesNotContain(OBJECT_KEY, "private prompt");
        }
        assertThat(MDC.get("generationId")).isEqualTo(previousGenerationId);
    }

    private static StoryboardGenerator provider(StoryboardProviderInfo info, Supplier<GeneratedStoryboard> result) {
        return new StoryboardGenerator() {
            @Override
            public StoryboardProviderInfo providerInfo() {
                return info;
            }

            @Override
            public GeneratedStoryboard generate(StoryboardGenerationRequest request) {
                return result.get();
            }
        };
    }

    private static final class StructuredLogCapture implements AutoCloseable {

        private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        private final LoggerContext encoderContext = new LoggerContext();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new OutputStreamAppender<>();
        private final List<ch.qos.logback.classic.Logger> loggers = List.of(
                context.getLogger(GenerationLifecycleMetrics.class),
                context.getLogger(DiscardedGenerationImageCleaner.class),
                context.getLogger("com.harudle.generation.diary.service.storyboard.StoryboardGenerationLogger"),
                context.getLogger(GenerationStructuredLoggingTest.class));

        private StructuredLogCapture() {
            encoderContext.putObject(Environment.class.getName(), new StandardEnvironment());
            StructuredLogEncoder encoder = new StructuredLogEncoder();
            encoder.setContext(encoderContext);
            encoder.setFormat("logstash");
            encoder.start();
            appender.setContext(context);
            appender.setEncoder(encoder);
            appender.setOutputStream(output);
            appender.start();
            loggers.forEach(logger -> logger.addAppender(appender));
        }

        private JsonNode assertEventAndFollowUp(String expectedEvent) {
            loggers.getLast().info(FOLLOW_UP_MESSAGE);
            List<String> lines = contents().lines().toList();
            assertThat(lines).hasSize(2);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode event = mapper.readTree(lines.getFirst());
            JsonNode followUp = mapper.readTree(lines.getLast());
            assertThat(event.path("event").asString()).isEqualTo(expectedEvent);
            assertThat(event.path("generationId").asString()).isEqualTo(GENERATION_ID.toString());
            assertThat(lines.getFirst().split("\"generationId\"", -1)).hasSize(2);
            assertThat(followUp.path("message").asString()).isEqualTo(FOLLOW_UP_MESSAGE);
            return event;
        }

        private String contents() {
            return output.toString(StandardCharsets.UTF_8);
        }

        private List<JsonNode> assertEventsAndFollowUp(String... expectedEvents) {
            loggers.getLast().info(FOLLOW_UP_MESSAGE);
            List<String> lines = contents().lines().toList();
            assertThat(lines).hasSize(expectedEvents.length + 1);
            ObjectMapper mapper = new ObjectMapper();
            List<JsonNode> events = lines.stream().limit(expectedEvents.length).map(mapper::readTree).toList();
            IntStream.range(0, expectedEvents.length).forEach(index -> {
                assertThat(events.get(index).path("event").asString()).isEqualTo(expectedEvents[index]);
                assertThat(events.get(index).path("generationId").asString()).isEqualTo(GENERATION_ID.toString());
                assertThat(lines.get(index).split("\"generationId\"", -1)).hasSize(2);
            });
            assertThat(mapper.readTree(lines.getLast()).path("message").asString()).isEqualTo(FOLLOW_UP_MESSAGE);
            return events;
        }

        @Override
        public void close() {
            loggers.forEach(logger -> logger.detachAppender(appender));
            appender.stop();
            encoderContext.stop();
        }
    }
}
