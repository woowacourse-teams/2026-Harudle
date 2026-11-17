package com.harudle.generation.adapter.out.openai;

import static com.harudle.generation.adapter.out.openai.OpenAiChatException.Kind.CONTENT_BLOCKED;
import static com.harudle.generation.adapter.out.openai.OpenAiChatException.Kind.HTTP;
import static com.harudle.generation.adapter.out.openai.OpenAiChatException.Kind.NETWORK;
import static com.harudle.generation.adapter.out.openai.OpenAiChatException.Kind.RESPONSE;
import static com.harudle.generation.adapter.out.openai.OpenAiChatException.Kind.TIMEOUT;
import static com.harudle.generation.adapter.out.openai.OpenAiChatException.Kind.TRUNCATED;

import com.harudle.generation.adapter.out.http.SingleAttemptHttpTransport;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.port.StoryboardAttemptContext;
import com.harudle.generation.diary.service.port.StoryboardAttemptContext.CancellationRegistration;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okio.BufferedSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI Chat Completions를 스트리밍으로 한 번만 호출한다.
 * 재시도는 하지 않으며, 실패는 원문 없이 {@link OpenAiChatException}의 분류로만 전달한다.
 */
public final class OpenAiChatClient implements AutoCloseable {

    public static final URI CHAT_COMPLETIONS_ENDPOINT = URI.create("https://api.openai.com/v1/chat/completions");
    private static final long MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Set<String> CONTENT_BLOCKED_CODES = Set.of("content_policy_violation", "content_filter");

    private final Call.Factory calls;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final URI endpoint;

    public OpenAiChatClient(Call.Factory calls, ObjectMapper mapper, String apiKey) {
        this(calls, mapper, apiKey, CHAT_COMPLETIONS_ENDPOINT);
    }

    public OpenAiChatClient(Call.Factory calls, ObjectMapper mapper, String apiKey, URI endpoint) {
        this.calls = Objects.requireNonNull(calls);
        this.mapper = Objects.requireNonNull(mapper);
        this.endpoint = Objects.requireNonNull(endpoint);
        validateEndpoint(endpoint);
        if (apiKey == null || !apiKey.matches("[\\x21-\\x7E]{1,8192}")) {
            throw new IllegalArgumentException("OpenAI API 키는 공백이나 제어 문자를 포함할 수 없습니다.");
        }
        this.apiKey = apiKey;
    }

    public static OkHttpClient httpClient(Duration timeout) {
        return SingleAttemptHttpTransport.builder(timeout).build();
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
                throw httpFailure(response);
            }
            if (response.body() == null) {
                throw new OpenAiChatException(RESPONSE, null);
            }
            Completion completion = readResponse(response.body().source(), started);
            context.ifPresent(StoryboardAttemptContext::checkActive);
            return completion;
        } catch (AiGenerationException exception) {
            throw exception;
        } catch (InterruptedIOException exception) {
            context.ifPresent(StoryboardAttemptContext::checkActive);
            call.cancel();
            throw new OpenAiChatException(TIMEOUT, null);
        } catch (IOException exception) {
            context.ifPresent(StoryboardAttemptContext::checkActive);
            call.cancel();
            throw new OpenAiChatException(NETWORK, null);
        } catch (OpenAiChatException exception) {
            context.ifPresent(StoryboardAttemptContext::checkActive);
            throw exception;
        } catch (RuntimeException exception) {
            context.ifPresent(StoryboardAttemptContext::checkActive);
            throw new OpenAiChatException(RESPONSE, null);
        }
    }

    private OpenAiChatException httpFailure(okhttp3.Response response) {
        if (response.code() != 400 || response.body() == null) {
            return new OpenAiChatException(HTTP, response.code());
        }
        try (var body = response.peekBody(MAX_RESPONSE_BYTES + 1)) {
            if (body.contentLength() <= MAX_RESPONSE_BYTES) {
                return responseFailure(mapper.readTree(body.string()), response.code());
            }
        } catch (IOException | RuntimeException ignored) {
            // 오류 본문을 읽거나 해석하지 못해도 기존 HTTP 실패 분류는 유지한다.
        }
        return new OpenAiChatException(HTTP, response.code());
    }

    private static OpenAiChatException responseFailure(JsonNode response, Integer statusCode) {
        String code = response == null ? "" : response.path("error").path("code").asString("");
        return new OpenAiChatException(CONTENT_BLOCKED_CODES.contains(code) ? CONTENT_BLOCKED : HTTP, statusCode);
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
            bytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
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
        Timing timing = new Timing(headers, state.firstAnswer, elapsed(started));
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
            throw responseFailure(event, null);
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
        JsonNode refusal = delta.path("refusal");
        if (refusal.isString() && !refusal.asString().isBlank()) {
            state.refused = true;
        }
        JsonNode content = delta.path("content");
        if (content.isString()) {
            if (state.firstAnswer == null && !content.asString().isBlank()) {
                state.firstAnswer = elapsed(state.started);
            }
            state.content.append(content.asString());
        }
    }

    private Completion parseJson(JsonNode json) {
        if (json == null || !json.isObject()) {
            throw new OpenAiChatException(RESPONSE, null);
        }
        if (json.has("error")) {
            throw responseFailure(json, null);
        }
        JsonNode choice = json.path("choices").path(0);
        JsonNode refusal = choice.path("message").path("refusal");
        boolean refused = refusal.isString() && !refusal.asString().isBlank();
        String reason = choice.path("finish_reason").asString("");
        requireCompleted(reason, refused);
        return new Completion(choice.path("message").path("content").asString(""),
                usage(json.get("usage")), reason, null);
    }

    private static Completion finish(StreamState state) {
        requireCompleted(state.finishReason, state.refused);
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

    private static void requireCompleted(String reason, boolean refused) {
        if (refused || "content_filter".equals(reason)) {
            throw new OpenAiChatException(CONTENT_BLOCKED, null);
        }
        if ("length".equals(reason)) {
            throw new OpenAiChatException(TRUNCATED, null);
        }
        if (!"stop".equals(reason)) {
            throw new OpenAiChatException(RESPONSE, null);
        }
    }

    private static void requireResponseSize(long bytes) {
        if (bytes > MAX_RESPONSE_BYTES) {
            throw new OpenAiChatException(RESPONSE, null);
        }
    }

    private static void validateEndpoint(URI endpoint) {
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("OpenAI 엔드포인트는 인증 정보 없는 HTTPS 주소여야 합니다.");
        }
    }

    private static double elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000_000.0;
    }

    public record Request(String model, String systemPrompt, String userPrompt, int maxOutputTokens,
                          String reasoningEffort, boolean strictSchema, Map<String, Object> schema) {

        public Request {
            Objects.requireNonNull(systemPrompt);
            Objects.requireNonNull(userPrompt);
            schema = Map.copyOf(schema);
            if (model == null || model.isBlank() || maxOutputTokens < 1
                    || reasoningEffort != null && reasoningEffort.isBlank()) {
                throw new IllegalArgumentException("OpenAI 요청 모델, 출력 한도, 추론 강도를 확인해 주세요.");
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
            payload.put("max_completion_tokens", maxOutputTokens);
            if (reasoningEffort != null) {
                payload.put("reasoning_effort", reasoningEffort);
            }
            payload.put("store", false);
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

    public record Timing(double responseHeadersSeconds, Double firstAnswerSeconds, double responseCompletedSeconds) {
    }

    private static final class StreamState {
        private final long started;
        private final StringBuilder content = new StringBuilder();
        private Usage usage;
        private String finishReason;
        private Double firstAnswer;
        private boolean refused;

        private StreamState(long started) {
            this.started = started;
        }
    }
}
