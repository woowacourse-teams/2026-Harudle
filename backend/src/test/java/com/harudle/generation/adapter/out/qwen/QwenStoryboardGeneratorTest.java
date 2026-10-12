package com.harudle.generation.adapter.out.qwen;

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
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class QwenStoryboardGeneratorTest {

    private static final String KEY = "sk-sp-test-key.with+ascii==";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Call.Factory calls = mock(Call.Factory.class);
    private final Call call = mock(Call.class);
    private final ExternalApiLogger logger = mock(ExternalApiLogger.class);
    private final StoryboardJsonCodec codec = new StoryboardJsonCodec(mapper, new StoryboardResponseMapper());
    private final QwenStoryboardSettings settings = QwenStoryboardSettings.defaults();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final StoryboardGenerationRequest input = new StoryboardGenerationRequest("발표 전에 노트북이 업데이트됐다.",
            "Keep the facts. Return storyboard JSON.");
    private final QwenStoryboardGenerator generator = new QwenStoryboardGenerator(
            new QwenChatClient(calls, mapper, KEY), settings, codec, logger,
            new QwenStageMetrics(meterRegistry, settings.model()));

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
        assertThat(new DiaryImagePromptRenderer().render(result.storyboard()))
                .contains("준비 완료", "작은 화면 출동", "upper-left");
        ArgumentCaptor<Request> request = ArgumentCaptor.forClass(Request.class);
        verify(calls, times(1)).newCall(request.capture());
        verify(call, times(1)).execute();
        assertThat(request.getValue().url().toString()).isEqualTo(QwenChatClient.TOKEN_PLAN_ENDPOINT.toString());
        assertThat(request.getValue().header("Authorization")).isEqualTo("Bearer " + KEY);
        verifyNoInteractions(logger);
        assertThat(meterRegistry.get("harudle.qwen.stage.calls")
                .tags("outcome", "success", "failureType", "none").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("harudle.qwen.tokens").tag("kind", "candidate").counter().count())
                .isEqualTo(390);
    }

    @Test
    @DisplayName("기본 Max 1024 설정과 변경 가능한 모델 설정을 같은 요청 구성기로 전달한다")
    void configureModelWithoutAddingProviderSwitches() {
        var request = new QwenStoryboardRequestFactory(settings, codec).create(input);
        var json = mapper.valueToTree(request.payload());
        assertThat(json.path("model").asString()).isEqualTo("qwen3.8-max");
        assertThat(json.path("thinking_budget").asInt()).isEqualTo(1024);
        assertThat(json.path("enable_thinking").asBoolean()).isTrue();
        assertThat(json.path("messages").path(0).path("content").asString())
                .startsWith(input.storyboardPromptText()).contains("<output_schema>");
        assertThat(mapper.writeValueAsString(request.payload())).doesNotContain(KEY, "propertyOrdering");
        var future = new QwenStoryboardSettings("qwen-future-model", 4096, true, 2048, true,
                Duration.ofSeconds(90));
        var futureRequest = new QwenStoryboardRequestFactory(future, codec).create(input);
        assertThat(futureRequest.model()).isEqualTo("qwen-future-model");
        assertThat(futureRequest.systemPrompt()).isEqualTo(input.storyboardPromptText());
        assertThat(mapper.valueToTree(futureRequest.payload()).path("response_format").path("type").asString())
                .isEqualTo("json_schema");
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
            assertThat(metadata.getValue().provider()).isEqualTo("qwen");
            assertThat(metadata.getValue().providerStatus()).isEqualTo(String.valueOf(status));
        }
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
        assertFailure(AiGenerationErrorType.PROVIDER_ERROR);
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

        assertThat(meterRegistry.get("harudle.qwen.stage.calls")
                .tags("outcome", "failure", "failureType", "RESPONSE_PROCESSING_ERROR").counter().count())
                .isEqualTo(1);
        assertThat(meterRegistry.get("harudle.qwen.tokens").tag("kind", "total").counter().count()).isEqualTo(600);
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
        var usage = new QwenChatClient.Usage(100, 500, null, 600, null);
        assertThat(QwenStoryboardGenerator.tokenUsage(usage, true).candidateTokenCount()).isNull();
        assertThat(QwenStoryboardGenerator.tokenUsage(usage, true).thoughtTokenCount()).isNull();
        assertThat(QwenStoryboardGenerator.tokenUsage(usage, false).candidateTokenCount()).isEqualTo(500);
        assertThat(QwenStoryboardGenerator.tokenUsage(null, true)).isNull();
    }

    @Test
    @DisplayName("잘못된 설정과 Token Plan 이외 키는 HTTP 요청 전에 거절한다")
    void validateConfigurationBeforeSending() {
        assertThatThrownBy(() -> new QwenChatClient(calls, mapper, "sk-ordinary-key"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sk-sp-");
        assertThatThrownBy(() -> new QwenChatClient(calls, mapper, KEY + "\n"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QwenChatClient(calls, mapper, KEY,
                URI.create("https://user:password@example.com/chat/completions")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QwenStoryboardSettings("", 8192, true, 1024, false, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QwenStoryboardSettings("qwen3.8-max", 8192, false, 1024, false,
                Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(calls);
    }

    private void assertFailure(AiGenerationErrorType expected) {
        assertThatThrownBy(() -> generator.generate(input))
                .isInstanceOfSatisfying(AiGenerationException.class, error -> {
                    assertThat(error.errorType()).isEqualTo(expected);
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
                .request(new Request.Builder().url(QwenChatClient.TOKEN_PLAN_ENDPOINT.toString()).build())
                .protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build());
    }
}
