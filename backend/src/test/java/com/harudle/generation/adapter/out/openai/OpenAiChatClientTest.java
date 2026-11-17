package com.harudle.generation.adapter.out.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.generation.adapter.out.storyboard.StoryboardTestFixtures;
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
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import okio.Timeout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OpenAiChatClientTest {

    private static final String API_KEY = "sk-proj-client-test";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Call.Factory calls = mock(Call.Factory.class);
    private final Call call = mock(Call.class);
    private final OpenAiChatClient client = new OpenAiChatClient(calls, mapper, API_KEY);
    private final OpenAiChatClient.Request request = new OpenAiChatClient.Request("gpt-6-luna", "system prompt",
            "일기", 8192, "medium", true, Map.of("type", "object"));

    @Test
    @DisplayName("Chat Completions 주소에 인증 헤더와 스트리밍, 출력 한도, 추론 강도, 저장 거부, 스키마를 담아 보낸다")
    void sendChatCompletionsRequest() throws Exception {
        respond(ResponseBody.create(contentEvent(StoryboardTestFixtures.storyboardJson()) + finishEvent("stop")
                + "data: [DONE]\n\n", MediaType.get("text/event-stream")));

        client.generate(request);

        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
        verify(calls).newCall(captor.capture());
        Request sent = captor.getValue();
        assertThat(sent.url().toString()).isEqualTo("https://api.openai.com/v1/chat/completions");
        assertThat(sent.header("Authorization")).isEqualTo("Bearer " + API_KEY);
        Buffer body = new Buffer();
        sent.body().writeTo(body);
        JsonNode payload = mapper.readTree(body.readUtf8());
        assertThat(payload.path("model").asString()).isEqualTo("gpt-6-luna");
        assertThat(payload.path("stream").asBoolean()).isTrue();
        assertThat(payload.path("stream_options").path("include_usage").asBoolean()).isTrue();
        assertThat(payload.path("max_completion_tokens").asInt()).isEqualTo(8192);
        assertThat(payload.has("max_tokens")).isFalse();
        assertThat(payload.path("reasoning_effort").asString()).isEqualTo("medium");
        assertThat(payload.path("store").asBoolean(true)).isFalse();
        assertThat(payload.path("response_format").path("type").asString()).isEqualTo("json_schema");
        assertThat(payload.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(payload.path("messages").path(0).path("role").asString()).isEqualTo("system");
        assertThat(payload.path("messages").path(1).path("content").asString()).isEqualTo("일기");
    }

    @Test
    @DisplayName("추론 강도를 지정하지 않으면 요청에서 생략하고 엄격 스키마가 아니면 JSON 객체 응답만 요구한다")
    void omitReasoningEffortAndUseJsonObjectFormat() {
        var plain = new OpenAiChatClient.Request("gpt-6-luna", "s", "u", 100, null, false, Map.of());

        Map<String, Object> payload = plain.payload();

        assertThat(payload).doesNotContainKey("reasoning_effort");
        assertThat(payload.get("response_format")).isEqualTo(Map.of("type", "json_object"));
        assertThat(plain.toString()).isEqualTo("Request[content=<redacted>]");
    }

    @Test
    @DisplayName("SSE의 최종 JSON과 안전한 사용량 및 수신 시각만 남긴다")
    void streamFinalJsonUsageAndTiming() throws Exception {
        String json = StoryboardTestFixtures.storyboardJson();
        String stream = ": keep-alive\n\n"
                + contentEvent(json.substring(0, 150)) + contentEvent(json.substring(150))
                + finishEvent("stop")
                + event(Map.of("choices", List.of(), "usage", Map.of("prompt_tokens", 100,
                        "completion_tokens", 200, "total_tokens", 300, "api_key", "must-not-be-retained",
                        "completion_tokens_details", Map.of("reasoning_tokens", 150),
                        "prompt_tokens_details", Map.of("cached_tokens", 40))))
                + "data: [DONE]\n\n";
        respond(ResponseBody.create(stream, MediaType.get("text/event-stream")));

        var result = client.generate(request);

        assertThat(result.content()).isEqualTo(json);
        assertThat(result.finishReason()).isEqualTo("stop");
        assertThat(result.usage()).isEqualTo(new OpenAiChatClient.Usage(100, 200, 150, 300, 40));
        assertThat(result.timing().firstAnswerSeconds()).isGreaterThanOrEqualTo(0);
        assertThat(result.timing().responseCompletedSeconds())
                .isGreaterThanOrEqualTo(result.timing().firstAnswerSeconds());
        assertThat(mapper.writeValueAsString(result)).doesNotContain("must-not-be-retained");
    }

    @Test
    @DisplayName("스트리밍이 아닌 JSON 응답도 해석하고 응답에 섞인 API 키는 가린다")
    void parseNonStreamingJsonAndRedactApiKey() throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", "{\"echo\":\"" + API_KEY + "\"}"),
                        "finish_reason", "stop")),
                "usage", Map.of("prompt_tokens", 1, "completion_tokens", 2, "total_tokens", 3)));
        respond(ResponseBody.create(body, MediaType.get("application/json")));

        var result = client.generate(request);

        assertThat(result.content()).isEqualTo("{\"echo\":\"[REDACTED]\"}");
        assertThat(result.usage().totalTokens()).isEqualTo(3);
    }

    @ParameterizedTest
    @CsvSource({"length,TRUNCATED", "content_filter,CONTENT_BLOCKED", "'',RESPONSE"})
    @DisplayName("출력 잘림과 콘텐츠 필터, 정상 종료 사유 누락은 성공으로 처리하지 않는다")
    void rejectIncompleteFinishReasons(String finishReason, OpenAiChatException.Kind expected) throws Exception {
        var completion = finishReason.isEmpty() ? Map.of("delta", Map.of())
                : Map.of("delta", Map.of(), "finish_reason", finishReason);
        String stream = contentEvent(StoryboardTestFixtures.storyboardJson())
                + event(Map.of("choices", List.of(completion)))
                + "data: [DONE]\n\n";
        respond(ResponseBody.create(stream, MediaType.get("text/event-stream")));

        assertThatThrownBy(() -> client.generate(request)).isInstanceOfSatisfying(OpenAiChatException.class,
                failure -> assertThat(failure.kind()).isEqualTo(expected));
    }

    @Test
    @DisplayName("모델이 응답을 거절하면 정상 종료 신호가 있어도 콘텐츠 차단으로 분류한다")
    void classifyRefusalAsContentBlocked() throws Exception {
        String stream = event(Map.of("choices", List.of(Map.of("delta", Map.of("refusal", "I can't help")))))
                + finishEvent("stop") + "data: [DONE]\n\n";
        respond(ResponseBody.create(stream, MediaType.get("text/event-stream")));

        assertThatThrownBy(() -> client.generate(request)).isInstanceOfSatisfying(OpenAiChatException.class,
                failure -> {
                    assertThat(failure.kind()).isEqualTo(OpenAiChatException.Kind.CONTENT_BLOCKED);
                    assertThat(failure.getMessage()).doesNotContain("I can't help");
                });
    }

    @Test
    @DisplayName("스트림 중간의 오류 이벤트는 원문 없이 실패로 분류한다")
    void classifyStreamErrorEvent() throws Exception {
        String stream = contentEvent("{") + event(Map.of("error", Map.of("code", "server_error",
                "message", "private provider detail")));
        respond(ResponseBody.create(stream, MediaType.get("text/event-stream")));

        assertThatThrownBy(() -> client.generate(request)).isInstanceOfSatisfying(OpenAiChatException.class,
                failure -> {
                    assertThat(failure.kind()).isEqualTo(OpenAiChatException.Kind.HTTP);
                    assertThat(failure.getMessage()).doesNotContain("private provider detail");
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 429, 500, 503})
    @DisplayName("200이 아닌 HTTP 응답은 상태 코드를 유지한 HTTP 실패로 분류한다")
    void classifyHttpFailures(int status) throws Exception {
        respond(status, ResponseBody.create("{\"error\":{\"code\":\"rate_limit_exceeded\"}}",
                MediaType.get("application/json")));

        assertThatThrownBy(() -> client.generate(request)).isInstanceOfSatisfying(OpenAiChatException.class,
                failure -> {
                    assertThat(failure.kind()).isEqualTo(OpenAiChatException.Kind.HTTP);
                    assertThat(failure.statusCode()).isEqualTo(status);
                });
        verify(call, times(1)).execute();
    }

    @Test
    @DisplayName("400 응답의 콘텐츠 정책 오류 코드는 콘텐츠 차단으로 분류한다")
    void classifyContentPolicyViolation() throws Exception {
        respond(400, ResponseBody.create("{\"error\":{\"code\":\"content_policy_violation\"}}",
                MediaType.get("application/json")));

        assertThatThrownBy(() -> client.generate(request)).isInstanceOfSatisfying(OpenAiChatException.class,
                failure -> {
                    assertThat(failure.kind()).isEqualTo(OpenAiChatException.Kind.CONTENT_BLOCKED);
                    assertThat(failure.statusCode()).isEqualTo(400);
                });
    }

    @Test
    @DisplayName("HTTP 헤더 수신 이후 본문 읽기 중 발생한 타임아웃도 취소하며 재시도하지 않는다")
    void cancelTimeoutWhileReadingResponseBody() throws Exception {
        BufferedSource source = Okio.buffer(new Source() {
            @Override
            public long read(Buffer sink, long byteCount) throws InterruptedIOException {
                throw new InterruptedIOException("private cause");
            }

            @Override
            public Timeout timeout() {
                return Timeout.NONE;
            }

            @Override
            public void close() {
            }
        });
        ResponseBody body = new ResponseBody() {
            @Override
            public MediaType contentType() {
                return MediaType.get("text/event-stream");
            }

            @Override
            public long contentLength() {
                return -1;
            }

            @Override
            public BufferedSource source() {
                return source;
            }
        };
        respond(body);
        assertThatThrownBy(() -> client.generate(request))
                .isInstanceOfSatisfying(OpenAiChatException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(OpenAiChatException.Kind.TIMEOUT);
                    assertThat(failure.getMessage()).doesNotContain("private cause");
                    assertThat(failure.getCause()).isNull();
                });
        verify(call).cancel();
        verify(call, times(1)).execute();
        verify(calls, times(1)).newCall(any(Request.class));
    }

    @Test
    @DisplayName("연결 오류는 호출을 취소하고 네트워크 실패로 분류한다")
    void classifyNetworkFailure() throws Exception {
        when(calls.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenThrow(new IOException("private network cause"));

        assertThatThrownBy(() -> client.generate(request))
                .isInstanceOfSatisfying(OpenAiChatException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(OpenAiChatException.Kind.NETWORK);
                    assertThat(failure.getMessage()).doesNotContain("private network cause");
                });
        verify(call).cancel();
    }

    @Test
    @DisplayName("한 이벤트와 전체 스트림의 크기를 제한해 과도한 응답을 계속 누적하지 않는다")
    void boundSingleEventAndTotalResponseSize() throws Exception {
        String largeEvent = contentEvent("a".repeat(2 * 1024 * 1024 + 1));
        respond(ResponseBody.create(largeEvent, MediaType.get("text/event-stream")));
        assertThatThrownBy(() -> client.generate(request)).isInstanceOfSatisfying(OpenAiChatException.class,
                error -> assertThat(error.kind()).isEqualTo(OpenAiChatException.Kind.RESPONSE));
        String smallerEvent = contentEvent("x".repeat(1_100_000));
        respond(ResponseBody.create(smallerEvent + smallerEvent, MediaType.get("text/event-stream")));
        assertThatThrownBy(() -> client.generate(request)).isInstanceOfSatisfying(OpenAiChatException.class,
                error -> assertThat(error.kind()).isEqualTo(OpenAiChatException.Kind.RESPONSE));
    }

    @Test
    @DisplayName("오류 본문 읽기에 실패해도 이미 받은 HTTP 상태를 유지한다")
    void preserveHttpStatusWhenErrorBodyCannotBeRead() throws Exception {
        ResponseBody body = mock(ResponseBody.class);
        BufferedSource source = mock(BufferedSource.class);
        when(body.source()).thenReturn(source);
        when(source.peek()).thenReturn(source);
        when(source.request(anyLong())).thenThrow(new IOException("private provider cause"));
        respond(400, body);

        assertThatThrownBy(() -> client.generate(request))
                .isInstanceOfSatisfying(OpenAiChatException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(OpenAiChatException.Kind.HTTP);
                    assertThat(failure.statusCode()).isEqualTo(400);
                    assertThat(failure.getMessage()).doesNotContain("private provider cause");
                    assertThat(failure.getCause()).isNull();
                });
        verify(source).request(2 * 1024 * 1024 + 1L);
        verify(body).close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "sk has space", "sk-\n"})
    @DisplayName("비어 있거나 공백, 제어 문자가 있는 API 키는 거절한다")
    void rejectInvalidApiKey(String apiKey) {
        assertThatThrownBy(() -> new OpenAiChatClient(calls, mapper, apiKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OpenAI API 키는 공백이나 제어 문자를 포함할 수 없습니다.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://api.openai.com/v1/chat/completions",
            "https://user:pass@api.openai.com/v1/chat/completions",
            "https://api.openai.com/v1/chat/completions?x=1"})
    @DisplayName("HTTPS가 아니거나 인증 정보, 쿼리가 있는 주소는 거절한다")
    void rejectUnsafeEndpoint(String endpoint) {
        assertThatThrownBy(() -> new OpenAiChatClient(calls, mapper, API_KEY, URI.create(endpoint)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("전체 호출 제한 시간을 설정하고 연결 재시도와 인증 헤더 리디렉션을 끈다")
    void applyEntireCallTimeoutAndSingleAttemptTransport() {
        var http = OpenAiChatClient.httpClient(Duration.ofMillis(500));
        assertThat(http.callTimeoutMillis()).isEqualTo(500);
        assertThat(http.readTimeoutMillis()).isEqualTo(500);
        assertThat(http.retryOnConnectionFailure()).isFalse();
        assertThat(http.followRedirects()).isFalse();
        assertThat(http.followSslRedirects()).isFalse();
        http.dispatcher().executorService().shutdown();
    }

    @Test
    @DisplayName("애플리케이션 종료 시 OpenAI 전용 HTTP 실행기를 정리한다")
    void closeHttpResources() {
        var http = OpenAiChatClient.httpClient(Duration.ofSeconds(180));
        var managedClient = new OpenAiChatClient(http, mapper, "sk-proj-resource-test");

        managedClient.close();
        managedClient.close();

        assertThat(http.dispatcher().executorService().isShutdown()).isTrue();
        assertThat(http.connectionPool().connectionCount()).isZero();
    }

    private String event(Object value) {
        return "data: " + mapper.writeValueAsString(value) + "\n\n";
    }

    private String contentEvent(String value) {
        return event(Map.of("choices", List.of(Map.of("delta", Map.of("content", value)))));
    }

    private String finishEvent(String reason) {
        return event(Map.of("choices", List.of(Map.of("delta", Map.of(), "finish_reason", reason))));
    }

    private void respond(ResponseBody body) throws Exception {
        respond(200, body);
    }

    private void respond(int status, ResponseBody body) throws Exception {
        when(calls.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(new Response.Builder()
                .request(new Request.Builder().url(OpenAiChatClient.CHAT_COMPLETIONS_ENDPOINT.toString()).build())
                .protocol(Protocol.HTTP_1_1).code(status).message("fixture").body(body).build());
    }
}
