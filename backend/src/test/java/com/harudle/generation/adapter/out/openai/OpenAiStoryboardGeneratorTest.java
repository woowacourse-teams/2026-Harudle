package com.harudle.generation.adapter.out.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.common.logging.ExternalApiFailure;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.adapter.out.gemini.DiaryImagePromptRenderer;
import com.harudle.generation.adapter.out.storyboard.StoryboardJsonCodec;
import com.harudle.generation.adapter.out.storyboard.StoryboardResponseMapper;
import com.harudle.generation.adapter.out.storyboard.StoryboardTestFixtures;
import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.exception.AiProviderFailureType;
import com.harudle.generation.diary.service.port.StoryboardAttemptContext;
import com.harudle.generation.diary.service.port.StoryboardAttemptContext.CancellationReason;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Timeout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class OpenAiStoryboardGeneratorTest {

    private static final String KEY = "sk-proj-test-key.with+ascii==";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Call.Factory calls = mock(Call.Factory.class);
    private final Call call = mock(Call.class);
    private final ExternalApiLogger logger = mock(ExternalApiLogger.class);
    private final StoryboardJsonCodec codec = new StoryboardJsonCodec(mapper, new StoryboardResponseMapper());
    private final OpenAiStoryboardSettings settings = OpenAiStoryboardSettings.defaults();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final StoryboardGenerationRequest input = new StoryboardGenerationRequest("발표 전에 노트북이 업데이트됐다.",
            "Keep the facts. Return storyboard JSON.");
    private final OpenAiStoryboardGenerator generator = new OpenAiStoryboardGenerator(
            new OpenAiChatClient(calls, mapper, KEY), settings, codec, logger,
            new OpenAiStageMetrics(meterRegistry, settings.model()));

    @Test
    @DisplayName("이미 만료된 컨텍스트는 준비 오류로 바꾸지 않고 TIMEOUT으로 기록한다")
    void preserveExpiredContextBeforeRequest() {
        var context = new StoryboardAttemptContext(0, () -> 0);

        assertThatThrownBy(() -> generator.generate(input.withAttemptContext(context)))
                .isInstanceOfSatisfying(AiGenerationException.class, failure ->
                        assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.TIMEOUT));

        verifyNoInteractions(calls, logger);
        assertThat(meterRegistry.get("harudle.openai.stage.calls").tag("failureType", "TIMEOUT").counter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("명시적 취소는 내부 오류로 바꾸지 않고 CANCELLED로 전달한다")
    void preserveExplicitCancellationBeforeRequest() {
        var context = new StoryboardAttemptContext(Duration.ofSeconds(60).toNanos(), () -> 0);
        context.cancel(CancellationReason.CANCELLED);

        assertThatThrownBy(() -> generator.generate(input.withAttemptContext(context)))
                .isInstanceOfSatisfying(AiGenerationException.class, failure -> {
                    assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
                    assertThat(failure.providerFailureType()).isEqualTo(AiProviderFailureType.CANCELLED);
                });
        verifyNoInteractions(calls, logger);
    }

    @Test
    @DisplayName("HTTP 취소에 따른 I/O 오류를 원래 TIMEOUT 사유로 유지한다")
    void preserveDeadlineAfterHttpCancellation() throws Exception {
        var context = new StoryboardAttemptContext(Duration.ofSeconds(60).toNanos(), () -> 0);
        when(calls.newCall(any(Request.class))).thenReturn(call);
        when(call.timeout()).thenReturn(new Timeout());
        when(call.execute()).thenAnswer(invocation -> {
            context.cancel(CancellationReason.TIMEOUT);
            throw new IOException("private cause");
        });

        assertThatThrownBy(() -> generator.generate(input.withAttemptContext(context)))
                .isInstanceOfSatisfying(AiGenerationException.class, failure -> {
                    assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.TIMEOUT);
                    assertThat(failure.providerFailureType()).isEqualTo(AiProviderFailureType.UNKNOWN);
                    assertThat(failure.getCause()).isNull();
                });
        verify(call).cancel();
        verifyNoInteractions(logger);
    }

    @Test
    @DisplayName("만료 후 JSON 검증이 실패해도 응답 처리 오류 대신 TIMEOUT으로 기록한다")
    void preserveDeadlineAfterResponseValidationFailure() throws Exception {
        AtomicLong clock = new AtomicLong();
        var context = new StoryboardAttemptContext(Duration.ofSeconds(60).toNanos(), clock::get);
        var failingCodec = mock(StoryboardJsonCodec.class);
        when(failingCodec.schemaJson()).thenReturn("{}");
        when(failingCodec.decode(any())).thenAnswer(invocation -> {
            clock.set(Duration.ofSeconds(61).toNanos());
            throw new IllegalArgumentException("fixture");
        });
        when(call.timeout()).thenReturn(new Timeout());
        respond(200, completion(StoryboardTestFixtures.storyboardJson(), "stop", Map.of()));
        var validatingGenerator = new OpenAiStoryboardGenerator(new OpenAiChatClient(calls, mapper, KEY), settings,
                failingCodec, logger, new OpenAiStageMetrics(meterRegistry, settings.model()));

        assertThatThrownBy(() -> validatingGenerator.generate(input.withAttemptContext(context)))
                .isInstanceOfSatisfying(AiGenerationException.class, failure ->
                        assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.TIMEOUT));
        assertThat(meterRegistry.get("harudle.openai.stage.calls").tag("failureType", "TIMEOUT").counter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("기존 포트로 생성하고 이미지 생성기가 사용하는 동일한 스토리보드를 반환한다")
    void implementExistingPortAndProduceImageCompatibleStoryboard() throws Exception {
        respond(200, completion(StoryboardTestFixtures.storyboardJson(), "stop", Map.of(
                "prompt_tokens", 100, "completion_tokens", 500, "total_tokens", 600,
                "completion_tokens_details", Map.of("reasoning_tokens", 110))));
        StoryboardGenerator port = generator;

        var result = port.generate(input);

        assertThat(result.storyboard().panels()).hasSize(4);
        assertThat(result.tokenUsage().promptTokenCount()).isEqualTo(100);
        assertThat(result.tokenUsage().candidateTokenCount()).isEqualTo(390);
        assertThat(result.tokenUsage().thoughtTokenCount()).isEqualTo(110);
        assertThat(result.tokenUsage().totalTokenCount()).isEqualTo(600);
        assertThat(result.providerInfo().provider()).isEqualTo("openai");
        assertThat(result.providerInfo().model()).isEqualTo("gpt-6-luna");
        assertThat(new DiaryImagePromptRenderer().render(result.storyboard()))
                .contains("준비 완료", "작은 화면 출동", "upper-left");
        ArgumentCaptor<Request> request = ArgumentCaptor.forClass(Request.class);
        verify(calls, times(1)).newCall(request.capture());
        verify(call, times(1)).execute();
        assertThat(request.getValue().url().toString())
                .isEqualTo(OpenAiChatClient.CHAT_COMPLETIONS_ENDPOINT.toString());
        assertThat(request.getValue().header("Authorization")).isEqualTo("Bearer " + KEY);
        verifyNoInteractions(logger);
        assertThat(meterRegistry.get("harudle.openai.stage.calls")
                .tags("outcome", "success", "failureType", "none").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("harudle.openai.tokens").tag("kind", "candidate").counter().count())
                .isEqualTo(390);
    }

    @Test
    @DisplayName("기본 설정은 스키마를 시스템 프롬프트에 넣고 JSON 객체 응답과 추론 강도를 요청한다")
    void buildDefaultRequestWithSchemaInPrompt() {
        var request = new OpenAiStoryboardRequestFactory(settings, codec).create(input);
        var json = mapper.valueToTree(request.payload());

        assertThat(json.path("model").asString()).isEqualTo("gpt-6-luna");
        assertThat(json.path("reasoning_effort").asString()).isEqualTo("medium");
        assertThat(json.path("max_completion_tokens").asInt()).isEqualTo(8192);
        assertThat(json.path("response_format").path("type").asString()).isEqualTo("json_object");
        assertThat(json.path("messages").path(0).path("content").asString())
                .startsWith(input.storyboardPromptText())
                .contains("<output_schema>", "Return only one JSON object")
                .doesNotContain("<qwen_caption_focus>");
        assertThat(mapper.writeValueAsString(request.payload())).doesNotContain(KEY, "propertyOrdering");
    }

    @Test
    @DisplayName("엄격 스키마를 켜면 프롬프트의 스키마 안내 대신 json_schema 응답 형식을 사용한다")
    void buildStrictSchemaRequest() {
        var strict = new OpenAiStoryboardSettings("gpt-6-luna-future", 4096, null, true, Duration.ofSeconds(90));

        var request = new OpenAiStoryboardRequestFactory(strict, codec).create(input);

        assertThat(request.model()).isEqualTo("gpt-6-luna-future");
        assertThat(request.systemPrompt()).startsWith(input.storyboardPromptText())
                .doesNotContain("<output_schema>").contains("Return only one JSON object");
        var json = mapper.valueToTree(request.payload());
        assertThat(json.path("response_format").path("type").asString()).isEqualTo("json_schema");
        assertThat(json.has("reasoning_effort")).isFalse();
    }

    @Test
    @DisplayName("색상 패널 번호가 소품 위치와 달라도 공통 매퍼가 실제 등장 패널로 계산한다")
    void deriveColorPanelNumbersFromProps() throws Exception {
        respond(200, completion(StoryboardTestFixtures.storyboardJsonWithMismatchedColorPanels(),
                "stop", Map.of()));

        var storyboard = generator.generate(input).storyboard();

        assertThat(storyboard.scenePlan().focalColors().getFirst().panelNumbers()).containsExactly(1, 2, 4);
        assertThat(storyboard.scenePlan().focalColors().getLast().panelNumbers()).containsExactly(3);
        verifyNoInteractions(logger);
    }

    @Test
    @DisplayName("HTTP 상태를 기존 실패 종류로 매핑하고 재호출하거나 응답의 비밀 값을 노출하지 않는다")
    void classifyHttpFailuresWithoutRetryOrProviderBody() throws Exception {
        for (int status : new int[]{401, 429, 408, 504, 503}) {
            reset(calls, call, logger);
            respond(status, "private diary and " + KEY);
            AiGenerationErrorType expected = switch (status) {
                case 429 -> AiGenerationErrorType.RATE_LIMITED;
                case 408, 504 -> AiGenerationErrorType.TIMEOUT;
                default -> AiGenerationErrorType.PROVIDER_ERROR;
            };
            assertThatThrownBy(() -> generator.generate(input))
                    .isInstanceOfSatisfying(AiGenerationException.class, error -> {
                        assertThat(error.errorType()).isEqualTo(expected);
                        assertThat(error.getMessage()).doesNotContain(KEY, "private diary");
                        assertThat(error.getCause()).isNull();
                    });
            verify(calls, times(1)).newCall(any(Request.class));
            verify(call, times(1)).execute();
            var metadata = ArgumentCaptor.forClass(ExternalApiFailure.class);
            verify(logger).warn(metadata.capture(), any(AiGenerationException.class));
            assertThat(metadata.getValue().provider()).isEqualTo("openai");
            assertThat(metadata.getValue().providerStatus()).isEqualTo(String.valueOf(status));
        }
    }

    @ParameterizedTest
    @CsvSource({"400,REQUEST_REJECTED", "401,AUTH_CONFIGURATION_ERROR", "403,AUTH_CONFIGURATION_ERROR",
            "404,REQUEST_REJECTED", "500,TRANSIENT_SERVER_ERROR", "501,UNKNOWN",
            "502,TRANSIENT_SERVER_ERROR", "503,TRANSIENT_SERVER_ERROR"})
    @DisplayName("API 제공자 오류 타입을 유지하면서 HTTP 원인 상세 유형을 구분한다")
    void normalizeHttpFailureDetailsWithoutExposingProviderResponse(int status, AiProviderFailureType expected)
            throws Exception {
        respond(status, "private diary and " + KEY);

        assertThatThrownBy(() -> generator.generate(input))
                .isInstanceOfSatisfying(AiGenerationException.class, exception -> {
                    assertThat(exception.errorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
                    assertThat(exception.providerFailureType()).isEqualTo(expected);
                    assertThat(exception.getMessage()).doesNotContain(KEY, "private diary");
                    assertThat(exception.getCause()).isNull();
                });
        verify(call, times(1)).execute();
    }

    @Test
    @DisplayName("OpenAI의 콘텐츠 정책 거절은 사용자 차단 코드와 WARN 로그로 전달한다")
    void classifyContentPolicyViolation() throws Exception {
        respond(400, mapper.writeValueAsString(Map.of("error", Map.of(
                "code", "content_policy_violation", "message", "private diary and " + KEY))));

        assertThatThrownBy(() -> generator.generate(input))
                .isInstanceOfSatisfying(AiGenerationException.class, failure -> {
                    assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.CONTENT_BLOCKED);
                    assertThat(failure.errorCode()).isEqualTo(GenerationErrorCode.AI_CONTENT_BLOCKED);
                    assertThat(failure.getMessage()).doesNotContain("private diary", KEY);
                    assertThat(failure.getCause()).isNull();
                });
        verify(call, times(1)).execute();
        var metadata = ArgumentCaptor.forClass(ExternalApiFailure.class);
        verify(logger).warn(metadata.capture(), any(AiGenerationException.class));
        assertThat(metadata.getValue().failureType()).isEqualTo("CONTENT_BLOCKED");
        assertThat(meterRegistry.get("harudle.openai.stage.calls")
                .tag("failureType", "CONTENT_BLOCKED").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("모델의 응답 거절과 content_filter 종료도 사용자 차단 코드로 전달한다")
    void classifyRefusalAndContentFilter() throws Exception {
        respond(200, mapper.writeValueAsString(Map.of("choices", List.of(Map.of(
                "message", Map.of("refusal", "private refusal text"), "finish_reason", "stop")))));
        assertThatThrownBy(() -> generator.generate(input))
                .isInstanceOfSatisfying(AiGenerationException.class, failure -> {
                    assertThat(failure.errorCode()).isEqualTo(GenerationErrorCode.AI_CONTENT_BLOCKED);
                    assertThat(failure.getMessage()).doesNotContain("private refusal text");
                });

        respond(200, completion(StoryboardTestFixtures.storyboardJson(), "content_filter", Map.of()));
        assertThatThrownBy(() -> generator.generate(input))
                .isInstanceOfSatisfying(AiGenerationException.class, failure ->
                        assertThat(failure.errorCode()).isEqualTo(GenerationErrorCode.AI_CONTENT_BLOCKED));
    }

    @Test
    @DisplayName("인터럽트로 중단된 OpenAI 통신은 상세 취소 유형을 보존한다")
    void preserveInterruptedCancellationDetails() throws Exception {
        when(calls.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenThrow(new InterruptedIOException("interrupted I/O"));
        Thread.currentThread().interrupt();
        try {
            assertFailure(AiGenerationErrorType.TIMEOUT, AiProviderFailureType.CANCELLED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(call).cancel();
    }

    @Test
    @DisplayName("타임아웃과 네트워크 실패는 취소 후 기존 오류로 반환하며 원인 메시지를 숨긴다")
    void cancelIoFailuresWithoutLeakingCauseOrRetrying() throws Exception {
        when(calls.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenThrow(new InterruptedIOException(KEY));
        assertFailure(AiGenerationErrorType.TIMEOUT);
        verify(call).cancel();
        reset(call);
        when(call.execute()).thenThrow(new IOException(KEY));
        assertFailure(AiGenerationErrorType.PROVIDER_ERROR, AiProviderFailureType.NETWORK_ERROR);
        verify(call).cancel();
        verify(calls, times(2)).newCall(any(Request.class));
    }

    @Test
    @DisplayName("유효한 JSON이어도 출력 제한으로 끝났으면 잘린 출력 오류로 분류한다")
    void rejectTruncatedCompletionBeforeMapping() throws Exception {
        respond(200, completion(StoryboardTestFixtures.storyboardJson(), "length", Map.of()));
        assertFailure(AiGenerationErrorType.OUTPUT_TRUNCATED);
    }

    @Test
    @DisplayName("응답 검증이 실패해도 호출 한 번과 이미 받은 토큰 사용량은 지표에 남긴다")
    void recordReceivedUsageWhenStoryboardValidationFails() throws Exception {
        respond(200, completion("invalid storyboard", "stop", Map.of(
                "prompt_tokens", 100, "completion_tokens", 500, "total_tokens", 600,
                "completion_tokens_details", Map.of("reasoning_tokens", 110))));

        assertFailure(AiGenerationErrorType.RESPONSE_PROCESSING_ERROR);

        assertThat(meterRegistry.get("harudle.openai.stage.calls")
                .tags("outcome", "failure", "failureType", "RESPONSE_PROCESSING_ERROR").counter().count())
                .isEqualTo(1);
        assertThat(meterRegistry.get("harudle.openai.tokens").tag("kind", "total").counter().count())
                .isEqualTo(600);
        verify(call, times(1)).execute();
    }

    @Test
    @DisplayName("응답 JSON과 공통 스키마 및 도메인 규칙 위반을 응답 처리 오류로 반환한다")
    void rejectInvalidResponseWithoutLeakingGeneratedText() throws Exception {
        List<String> bodies = List.of("not JSON " + KEY,
                StoryboardTestFixtures.storyboardJson().replace("\"panel_number\":1", "\"panel_number\":\"1\""),
                StoryboardTestFixtures.storyboardJson().replace("지금 업데이트?", "준비 완료"),
                StoryboardTestFixtures.storyboardJson().replace("작은 화면 출동", "가".repeat(25)));
        for (String body : bodies) {
            respond(200, completion(body, "stop", Map.of()));
            assertFailure(AiGenerationErrorType.RESPONSE_PROCESSING_ERROR);
        }
    }

    @Test
    @DisplayName("추론 토큰이 보고되지 않으면 최종 본문 토큰 수를 추측하지 않는다")
    void preserveUnknownTokenCounts() {
        var usage = new OpenAiChatClient.Usage(100, 500, null, 600, null);
        assertThat(OpenAiStoryboardGenerator.tokenUsage(usage).candidateTokenCount()).isNull();
        assertThat(OpenAiStoryboardGenerator.tokenUsage(usage).thoughtTokenCount()).isNull();
        assertThat(OpenAiStoryboardGenerator.tokenUsage(new OpenAiChatClient.Usage(100, 500, 0, 600, null))
                .candidateTokenCount()).isEqualTo(500);
        assertThat(OpenAiStoryboardGenerator.tokenUsage(null)).isNull();
    }

    @Test
    @DisplayName("잘못된 설정은 HTTP 요청 전에 거절한다")
    void validateSettingsBeforeSending() {
        assertThatThrownBy(() -> new OpenAiStoryboardSettings("", 8192, "medium", false, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpenAiStoryboardSettings("gpt-6-luna", 0, "medium", false,
                Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpenAiStoryboardSettings("gpt-6-luna", 8192, " ", false,
                Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpenAiStoryboardSettings("gpt-6-luna", 8192, "medium", false,
                Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(calls);
    }

    private void assertFailure(AiGenerationErrorType expected) {
        assertFailure(expected, AiProviderFailureType.UNKNOWN);
    }

    private void assertFailure(AiGenerationErrorType expected, AiProviderFailureType providerFailureType) {
        assertThatThrownBy(() -> generator.generate(input))
                .isInstanceOfSatisfying(AiGenerationException.class, error -> {
                    assertThat(error.errorType()).isEqualTo(expected);
                    assertThat(error.providerFailureType()).isEqualTo(providerFailureType);
                    assertThat(error.getMessage()).doesNotContain(KEY);
                    assertThat(error.getCause()).isNull();
                });
    }

    private String completion(String content, String finishReason, Map<String, Object> usage) {
        return mapper.writeValueAsString(Map.of("choices", List.of(Map.of(
                "message", Map.of("content", content), "finish_reason", finishReason)), "usage", usage));
    }

    private void respond(int code, String body) throws Exception {
        when(calls.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(new Response.Builder()
                .request(new Request.Builder().url(OpenAiChatClient.CHAT_COMPLETIONS_ENDPOINT.toString()).build())
                .protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build());
    }
}
