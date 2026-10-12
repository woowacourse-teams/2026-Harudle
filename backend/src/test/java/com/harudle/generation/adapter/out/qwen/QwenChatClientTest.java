package com.harudle.generation.adapter.out.qwen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.generation.adapter.out.storyboard.StoryboardJsonCodec;
import com.harudle.generation.adapter.out.storyboard.StoryboardResponseMapper;
import com.harudle.generation.adapter.out.storyboard.StoryboardTestFixtures;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import java.io.InterruptedIOException;
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
import tools.jackson.databind.json.JsonMapper;

class QwenChatClientTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Call.Factory calls = mock(Call.Factory.class);
    private final Call call = mock(Call.class);
    private final QwenChatClient client = new QwenChatClient(calls, mapper, "sk-sp-client-test");
    private final QwenChatClient.Request request = new QwenStoryboardRequestFactory(QwenStoryboardSettings.defaults(),
            new StoryboardJsonCodec(mapper, new StoryboardResponseMapper()))
            .create(new StoryboardGenerationRequest("일기", "JSON storyboard"));

    @Test
    @DisplayName("SSE의 최종 JSON과 안전한 사용량 및 수신 시각만 남기고 추론 내용은 버린다")
    void streamFinalJsonUsageAndTimingWithoutRetainingReasoning() throws Exception {
        String json = StoryboardTestFixtures.storyboardJson();
        String stream = ": heartbeat\n\n"
                + event(Map.of("choices", List.of(Map.of("delta", Map.of("reasoning_content", "secret reasoning")))))
                + contentEvent(json.substring(0, 150)) + contentEvent(json.substring(150))
                + event(Map.of("choices", List.of(Map.of("delta", Map.of(), "finish_reason", "stop"))))
                + event(Map.of("choices", List.of(), "usage", Map.of("prompt_tokens", 100,
                        "completion_tokens", 200, "total_tokens", 300, "api_key", "must-not-be-retained",
                        "completion_tokens_details", Map.of("reasoning_tokens", 150))))
                + "data: [DONE]\n\n";
        respond(ResponseBody.create(stream, MediaType.get("text/event-stream")));

        var result = client.generate(request);

        assertThat(result.content()).isEqualTo(json);
        assertThat(result.usage().reasoningTokens()).isEqualTo(150);
        assertThat(result.timing().firstReasoningSeconds()).isGreaterThanOrEqualTo(0);
        assertThat(result.timing().firstAnswerSeconds())
                .isGreaterThanOrEqualTo(result.timing().firstReasoningSeconds());
        assertThat(result.timing().responseCompletedSeconds())
                .isGreaterThanOrEqualTo(result.timing().firstAnswerSeconds());
        assertThat(mapper.writeValueAsString(result)).doesNotContain("secret reasoning", "must-not-be-retained");
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
                .isInstanceOfSatisfying(QwenChatException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(QwenChatException.Kind.TIMEOUT);
                    assertThat(failure.getMessage()).doesNotContain("private cause");
                    assertThat(failure.getCause()).isNull();
                });
        verify(call).cancel();
        verify(call, times(1)).execute();
        verify(calls, times(1)).newCall(any(Request.class));
    }

    @Test
    @DisplayName("한 이벤트와 전체 스트림의 크기를 제한해 과도한 응답을 계속 누적하지 않는다")
    void boundSingleEventAndTotalResponseSize() throws Exception {
        String largeEvent = contentEvent("a".repeat(2 * 1024 * 1024 + 1));
        respond(ResponseBody.create(largeEvent, MediaType.get("text/event-stream")));
        assertThatThrownBy(() -> client.generate(request)).isInstanceOfSatisfying(QwenChatException.class,
                error -> assertThat(error.kind()).isEqualTo(QwenChatException.Kind.RESPONSE));
        String smallerEvent = event(Map.of("choices", List.of(Map.of(
                "delta", Map.of("reasoning_content", "x".repeat(1_100_000))))));
        respond(ResponseBody.create(smallerEvent + smallerEvent, MediaType.get("text/event-stream")));
        assertThatThrownBy(() -> client.generate(request)).isInstanceOfSatisfying(QwenChatException.class,
                error -> assertThat(error.kind()).isEqualTo(QwenChatException.Kind.RESPONSE));
    }

    @Test
    @DisplayName("전체 호출 제한 시간을 설정하고 연결 재시도와 인증 헤더 리디렉션을 끈다")
    void applyEntireCallTimeoutAndSingleAttemptTransport() {
        var http = QwenChatClient.httpClient(Duration.ofMillis(500));
        assertThat(http.callTimeoutMillis()).isEqualTo(500);
        assertThat(http.readTimeoutMillis()).isEqualTo(500);
        assertThat(http.retryOnConnectionFailure()).isFalse();
        assertThat(http.followRedirects()).isFalse();
        assertThat(http.followSslRedirects()).isFalse();
        http.dispatcher().executorService().shutdown();
    }

    private String event(Object value) {
        return "data: " + mapper.writeValueAsString(value) + "\n\n";
    }

    private String contentEvent(String value) {
        return event(Map.of("choices", List.of(Map.of("delta", Map.of("content", value)))));
    }

    private void respond(ResponseBody body) throws Exception {
        when(calls.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(new Response.Builder()
                .request(new Request.Builder().url(QwenChatClient.TOKEN_PLAN_ENDPOINT.toString()).build())
                .protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body).build());
    }
}
