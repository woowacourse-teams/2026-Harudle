package com.harudle.generation.adapter.out.qwen;

import static com.harudle.generation.adapter.out.qwen.QwenChatException.Kind.HTTP;
import static com.harudle.generation.adapter.out.qwen.QwenChatException.Kind.NETWORK;
import static com.harudle.generation.adapter.out.qwen.QwenChatException.Kind.RESPONSE;
import static com.harudle.generation.adapter.out.qwen.QwenChatException.Kind.TIMEOUT;
import static com.harudle.generation.adapter.out.qwen.QwenChatException.Kind.TRUNCATED;

import com.harudle.generation.adapter.out.http.SingleAttemptHttpTransport;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.port.StoryboardAttemptContext;
import com.harudle.generation.diary.service.port.StoryboardAttemptContext.CancellationRegistration;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okio.BufferedSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class QwenChatClient implements AutoCloseable {

    public static final URI TOKEN_PLAN_ENDPOINT = URI.create(
            "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1/chat/completions");
    private static final long MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final Call.Factory calls;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final URI endpoint;

    public QwenChatClient(Call.Factory calls, ObjectMapper mapper, String apiKey) {
        this(calls, mapper, apiKey, TOKEN_PLAN_ENDPOINT);
    }

    public QwenChatClient(Call.Factory calls, ObjectMapper mapper, String apiKey, URI endpoint) {
        this.calls = Objects.requireNonNull(calls);
        this.mapper = Objects.requireNonNull(mapper);
        this.endpoint = Objects.requireNonNull(endpoint);
        validateEndpoint(endpoint);
        if (TOKEN_PLAN_ENDPOINT.getHost().equalsIgnoreCase(endpoint.getHost())) {
            requireTokenPlanKey(apiKey);
        }
        if (apiKey == null || !apiKey.matches("[\\x21-\\x7E]{1,8192}")) {
            throw new IllegalArgumentException("Qwen API 키는 공백이나 제어 문자를 포함할 수 없습니다.");
        }
        this.apiKey = apiKey;
    }

    public static OkHttpClient httpClient(Duration timeout) {
        return SingleAttemptHttpTransport.builder(timeout).build();
    }

    private static void requireTokenPlanKey(String key) {
        if (key == null || !key.matches("sk-sp-[\\x21-\\x7E]{1,8192}")) {
            throw new IllegalArgumentException("Qwen Token Plan 키는 sk-sp- 접두사의 전용 키여야 합니다.");
        }
    }

    public Completion generate(Request request) {
        return generate(request, Optional.empty());
    }

    public Completion generate(Request request, Optional<StoryboardAttemptContext> context) {
        Objects.requireNonNull(context).ifPresent(StoryboardAttemptContext::checkActive);
        long started = System.nanoTime();
        okhttp3.Request httpRequest = new okhttp3.Request.Builder().url(endpoint.toString())
                .header("Authorization", "Bearer " + apiKey).header("Accept", "text/event-stream")
                .post(RequestBody.create(mapper.writeValueAsString(request.payload()),
                        MediaType.get("application/json; charset=utf-8"))).build();
        Call call = calls.newCall(httpRequest);
        try (CancellationRegistration registration = registerCancellation(call, context)) {
            return execute(call, context, started);
        }
    }

    private CancellationRegistration registerCancellation(Call call, Optional<StoryboardAttemptContext> context) {
        if (context.isEmpty()) {
            return () -> { };
        }
        StoryboardAttemptContext attempt = context.orElseThrow();
        long remainingNanos = attempt.remainingTime().toNanos();
        if (remainingNanos > 0) {
            call.timeout().timeout(remainingNanos, TimeUnit.NANOSECONDS);
        }
        return attempt.onCancellation(call::cancel);
    }

    private Completion execute(Call call, Optional<StoryboardAttemptContext> context, long started) {
        context.ifPresent(StoryboardAttemptContext::checkActive);
        try (okhttp3.Response response = call.execute()) {
            if (response.code() != 200) {
                throw new QwenChatException(HTTP, response.code());
            }
            if (response.body() == null) {
                throw new QwenChatException(RESPONSE, null);
            }
            Completion completion = readResponse(response.body().source(), started);
            context.ifPresent(StoryboardAttemptContext::checkActive);
            return completion;
        } catch (AiGenerationException exception) {
            throw exception;
        } catch (InterruptedIOException exception) {
            context.ifPresent(StoryboardAttemptContext::checkActive);
            call.cancel();
            throw new QwenChatException(TIMEOUT, null);
        } catch (IOException exception) {
            context.ifPresent(StoryboardAttemptContext::checkActive);
            call.cancel();
            throw new QwenChatException(NETWORK, null);
        } catch (QwenChatException exception) {
            context.ifPresent(StoryboardAttemptContext::checkActive);
            throw exception;
        } catch (RuntimeException exception) {
            context.ifPresent(StoryboardAttemptContext::checkActive);
            throw new QwenChatException(RESPONSE, null);
        }
    }

    @Override
    public void close() {
        if (calls instanceof OkHttpClient httpClient) {
            httpClient.dispatcher().cancelAll();
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }

    private Completion readResponse(BufferedSource source, long started) throws IOException {
        StreamState state = new StreamState(started);
        double headers = elapsed(started);
        StringBuilder fallback = new StringBuilder();
        long bytes = 0;
        boolean streaming = false;
        String line;
        while ((line = readLine(source)) != null) {
            bytes += line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L;
            requireResponseSize(bytes);
            String data = line.startsWith("data:") ? line.substring(5).strip() : null;
            if ("[DONE]".equals(data)) {
                streaming = true;
                break;
            }
            if (data != null) {
                streaming = true;
                consumeEvent(state, data);
            } else if (!streaming && !line.startsWith(":")) {
                fallback.append(line).append('\n');
            }
        }
        Timing timing = new Timing(headers, state.firstReasoning, state.firstAnswer, elapsed(started));
        Completion completion = streaming ? finish(state) : parseJson(mapper.readTree(fallback.toString()));
        return new Completion(completion.content().replace(apiKey, "[REDACTED]"),
                completion.usage(), completion.finishReason(), timing);
    }

    private static String readLine(BufferedSource source) throws IOException {
        if (source.exhausted()) {
            return null;
        }
        try {
            return source.readUtf8LineStrict(MAX_RESPONSE_BYTES);
        } catch (EOFException exception) {
            requireResponseSize(source.getBuffer().size());
            return source.readUtf8();
        }
    }

    private void consumeEvent(StreamState state, String data) {
        if (data.isBlank()) {
            return;
        }
        JsonNode event = mapper.readTree(data);
        if (event.has("error")) {
            throw new QwenChatException(HTTP, null);
        }
        if (event.hasNonNull("usage")) {
            state.usage = usage(event.get("usage"));
        }
        JsonNode choice = event.path("choices").path(0);
        collectDelta(state, choice.path("delta"));
        if (choice.hasNonNull("finish_reason")) {
            state.finishReason = choice.path("finish_reason").asString();
        }
    }

    private static void collectDelta(StreamState state, JsonNode delta) {
        JsonNode reasoning = delta.path("reasoning_content");
        if (state.started > 0 && state.firstReasoning == null
                && reasoning.isString() && !reasoning.asString().isBlank()) {
            state.firstReasoning = elapsed(state.started);
        }
        JsonNode content = delta.path("content");
        if (content.isString()) {
            if (state.started > 0 && state.firstAnswer == null && !content.asString().isBlank()) {
                state.firstAnswer = elapsed(state.started);
            }
            state.content.append(content.asString());
        }
    }

    private Completion parseJson(JsonNode json) {
        if (json == null || !json.isObject()) {
            throw new QwenChatException(RESPONSE, null);
        }
        if (json.has("error")) {
            throw new QwenChatException(HTTP, null);
        }
        JsonNode choice = json.path("choices").path(0);
        String reason = choice.path("finish_reason").asString("");
        requireCompleted(reason);
        return new Completion(choice.path("message").path("content").asString(""),
                usage(json.get("usage")), reason, null);
    }

    private static Completion finish(StreamState state) {
        requireCompleted(state.finishReason);
        return new Completion(state.content.toString(), state.usage, state.finishReason, null);
    }

    private static Usage usage(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return new Usage(count(node.get("prompt_tokens")), count(node.get("completion_tokens")),
                count(node.path("completion_tokens_details").get("reasoning_tokens")),
                count(node.get("total_tokens")), count(node.path("prompt_tokens_details").get("cached_tokens")));
    }

    private static Integer count(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0) {
            return null;
        }
        return value.asInt();
    }

    private static void requireCompleted(String reason) {
        if ("length".equals(reason)) {
            throw new QwenChatException(TRUNCATED, null);
        }
        if (!"stop".equals(reason)) {
            throw new QwenChatException(RESPONSE, null);
        }
    }

    private static void requireResponseSize(long bytes) {
        if (bytes > MAX_RESPONSE_BYTES) {
            throw new QwenChatException(RESPONSE, null);
        }
    }

    private static void validateEndpoint(URI endpoint) {
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Qwen 엔드포인트는 인증 정보 없는 HTTPS 주소여야 합니다.");
        }
    }

    private static double elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000_000.0;
    }

    public record Request(String model, String systemPrompt, String userPrompt, int maxTokens,
                          boolean thinking, Integer thinkingBudget, boolean strictSchema, Map<String, Object> schema) {

        public Request {
            Objects.requireNonNull(systemPrompt);
            Objects.requireNonNull(userPrompt);
            schema = Map.copyOf(schema);
            if (model == null || model.isBlank() || maxTokens < 1
                    || thinkingBudget != null && (!thinking || thinkingBudget < 1)) {
                throw new IllegalArgumentException("Qwen 요청 모델, 출력 한도, 추론 설정을 확인해 주세요.");
            }
        }

        @Override
        public String toString() {
            return "Request[content=<redacted>]";
        }

        public Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("messages", List.of(Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)));
            payload.put("stream", true);
            payload.put("stream_options", Map.of("include_usage", true));
            payload.put("enable_thinking", thinking);
            payload.put("max_tokens", maxTokens);
            if (thinkingBudget != null) {
                payload.put("thinking_budget", thinkingBudget);
            }
            payload.put("response_format", strictSchema
                    ? Map.of("type", "json_schema", "json_schema", Map.of(
                            "name", "storyboard", "strict", true, "schema", schema))
                    : Map.of("type", "json_object"));
            return payload;
        }
    }

    public record Completion(String content, Usage usage, String finishReason, Timing timing) {
    }

    public record Usage(Integer promptTokens, Integer completionTokens, Integer reasoningTokens,
                        Integer totalTokens, Integer cachedTokens) {
    }

    public record Timing(double responseHeadersSeconds, Double firstReasoningSeconds, Double firstAnswerSeconds,
                         double responseCompletedSeconds) {
    }

    private static final class StreamState {
        private final long started;
        private final StringBuilder content = new StringBuilder();
        private Usage usage;
        private String finishReason;
        private Double firstReasoning;
        private Double firstAnswer;

        private StreamState(long started) {
            this.started = started;
        }
    }
}
