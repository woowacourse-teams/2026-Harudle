package com.harudle.generation.adapter.out.storyboard;

import com.harudle.generation.adapter.out.http.SingleAttemptHttpTransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.adapter.out.gemini.GeminiExceptionTranslator;
import com.harudle.generation.adapter.out.gemini.GeminiFailureReporter;
import com.harudle.generation.adapter.out.gemini.GeminiStageMetrics;
import com.harudle.generation.adapter.out.gemini.GeminiStoryboardGenerator;
import com.harudle.generation.adapter.out.gemini.client.ExpressGeminiClientFactory;
import com.harudle.generation.adapter.out.gemini.client.GeminiStoryboardCallScope;
import com.harudle.generation.adapter.out.qwen.QwenChatClient;
import com.harudle.generation.adapter.out.qwen.QwenStageMetrics;
import com.harudle.generation.adapter.out.qwen.QwenStoryboardGenerator;
import com.harudle.generation.adapter.out.qwen.QwenStoryboardSettings;
import com.harudle.generation.config.GeminiGenerationProperties;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.exception.AiProviderFailureType;
import com.harudle.generation.diary.service.port.StoryboardAttemptContext;
import com.harudle.generation.diary.service.port.StoryboardAttemptContext.CancellationReason;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import com.harudle.generation.diary.service.storyboard.StoryboardAttemptRunner;
import com.harudle.generation.diary.service.storyboard.StoryboardGenerationBudget;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class StoryboardHttpCancellationTest {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final StoryboardGenerationRequest request = new StoryboardGenerationRequest("일기", "프롬프트");
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final List<Client> sdkClients = new ArrayList<>();
    private final List<OkHttpClient> httpClients = new ArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final CountDownLatch received = new CountDownLatch(1);
    private final CountDownLatch bodySent = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService serverWorkers;
    private ExecutorService workers;
    private HttpHandler behavior;
    private String provider;

    @BeforeEach
    void setUp() throws IOException {
        workers = Executors.newFixedThreadPool(2);
        serverWorkers = Executors.newFixedThreadPool(4);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverWorkers);
        behavior = this::respondSuccess;
        server.createContext("/", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                requests.incrementAndGet();
                received.countDown();
                behavior.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        release.countDown();
        sdkClients.forEach(Client::close);
        httpClients.forEach(http -> {
            http.dispatcher().cancelAll();
            http.dispatcher().executorService().shutdown();
            http.connectionPool().evictAll();
        });
        workers.shutdownNow();
        if (server != null) {
            server.stop(0);
        }
        serverWorkers.shutdownNow();
        assertThat(workers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        assertThat(serverWorkers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"gemini,false", "gemini,true", "qwen,false", "qwen,true"})
    @DisplayName("HTTP 헤더 또는 본문 수신이 지연되면 실행기 TIMEOUT이 실제 Call을 취소한다")
    void cancelRealHttpCallAtDeadline(String selectedProvider, boolean bodyStarted) throws Exception {
        StoryboardGenerator generator = generator(selectedProvider);
        behavior = exchange -> delayResponse(exchange, bodyStarted);
        CountDownLatch stopped = new CountDownLatch(1);
        var runner = new StoryboardAttemptRunner(workers);
        StoryboardGenerator observed = timedRequest -> {
            try {
                return generator.generate(timedRequest);
            } finally {
                stopped.countDown();
            }
        };

        assertThatThrownBy(() -> runner.run(observed, request, Duration.ofSeconds(1),
                StoryboardGenerationBudget.start(Duration.ofSeconds(2))))
                .isInstanceOfSatisfying(AiGenerationException.class, failure -> {
                    assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.TIMEOUT);
                    assertThat(failure.providerFailureType()).isEqualTo(AiProviderFailureType.UNKNOWN);
                });

        assertThat(stopped.await(3, TimeUnit.SECONDS)).isTrue();
        if (bodyStarted) {
            assertThat(bodySent.getCount()).isZero();
        }
        assertThat(requests).hasValue(1);
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().isCanceled()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"gemini,false", "gemini,true", "qwen,false", "qwen,true"})
    @DisplayName("작업 스레드를 인터럽트하지 않아도 요청별 취소 콜백이 실제 HTTP 읽기를 중단한다")
    void cancelTransportWithoutInterruptingWorker(String selectedProvider, boolean bodyStarted) throws Exception {
        StoryboardGenerator generator = generator(selectedProvider);
        behavior = exchange -> delayResponse(exchange, bodyStarted);
        StoryboardAttemptContext context = context();
        var result = workers.submit(() -> catchThrowableOfType(
                () -> generator.generate(request.withAttemptContext(context)), AiGenerationException.class));
        assertThat(received.await(3, TimeUnit.SECONDS)).isTrue();
        if (bodyStarted) {
            assertThat(bodySent.await(3, TimeUnit.SECONDS)).isTrue();
        }

        context.cancel(CancellationReason.CANCELLED);
        AiGenerationException failure = result.get(3, TimeUnit.SECONDS);

        assertThat(failure.providerFailureType()).isEqualTo(AiProviderFailureType.CANCELLED);
        assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
        assertThat(calls.getFirst().isCanceled()).isTrue();
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gemini", "qwen"})
    @DisplayName("동시 요청 중 하나를 취소해도 다른 HTTP 요청을 취소하지 않는다")
    void isolateRealConcurrentHttpCalls(String selectedProvider) throws Exception {
        StoryboardGenerator generator = generator(selectedProvider);
        CountDownLatch healthyReceived = new CountDownLatch(1);
        CountDownLatch slowBlocked = new CountDownLatch(1);
        CountDownLatch releaseHealthy = new CountDownLatch(1);
        behavior = exchange -> {
            if (requests.get() == 1) {
                slowBlocked.countDown();
                await(release);
            } else {
                healthyReceived.countDown();
                await(releaseHealthy);
            }
            respondSuccess(exchange);
        };
        StoryboardAttemptContext cancelled = context();
        var first = workers.submit(() -> catchThrowableOfType(
                () -> generator.generate(request.withAttemptContext(cancelled)), AiGenerationException.class));
        assertThat(slowBlocked.await(3, TimeUnit.SECONDS)).isTrue();
        var healthy = workers.submit(() -> generator.generate(request.withAttemptContext(context())));
        try {
            assertThat(healthyReceived.await(3, TimeUnit.SECONDS)).isTrue();
            cancelled.cancel(CancellationReason.CANCELLED);

            assertThat(first.get(3, TimeUnit.SECONDS).providerFailureType()).isEqualTo(AiProviderFailureType.CANCELLED);
            assertThat(calls).hasSize(2);
            assertThat(calls.get(0).isCanceled()).isTrue();
            assertThat(calls.get(1).isCanceled()).isFalse();
        } finally {
            releaseHealthy.countDown();
        }

        assertThat(healthy.get(3, TimeUnit.SECONDS).storyboard().panels()).hasSize(4);
        assertThat(requests).hasValue(2);
    }

    @ParameterizedTest
    @CsvSource({"gemini,408", "gemini,429", "gemini,500", "gemini,502", "gemini,503", "gemini,504",
            "qwen,408", "qwen,429", "qwen,500", "qwen,502", "qwen,503", "qwen,504"})
    @DisplayName("HTTP 오류와 즉시 재시도 헤더가 있어도 서버에 한 번만 전송한다")
    void sendOnlyOnceOnHttpFailure(String selectedProvider, int status) throws Exception {
        StoryboardGenerator generator = generator(selectedProvider);
        behavior = exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "0");
            respond(exchange, status, mapper.writeValueAsBytes(Map.of("error",
                    Map.of("code", status, "status", "UNAVAILABLE", "message", "fixture"))));
        };

        var response = workers.submit(() -> catchThrowableOfType(
                () -> generator.generate(request), AiGenerationException.class));
        AiGenerationException failure = response.get(3, TimeUnit.SECONDS);

        assertThat(failure.errorType()).isEqualTo(httpFailureType(status));
        assertThat(requests).hasValue(1);
        assertThat(calls).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gemini", "qwen"})
    @DisplayName("서버가 응답 없이 연결을 끊어도 HTTP를 다시 전송하지 않는다")
    void sendOnlyOnceOnNetworkFailure(String selectedProvider) throws Exception {
        StoryboardGenerator generator = generator(selectedProvider);
        behavior = HttpExchange::close;

        var response = workers.submit(() -> catchThrowableOfType(
                () -> generator.generate(request), AiGenerationException.class));
        AiGenerationException failure = response.get(3, TimeUnit.SECONDS);

        assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
        assertThat(failure.providerFailureType()).isEqualTo(AiProviderFailureType.NETWORK_ERROR);
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gemini", "qwen"})
    @DisplayName("완료한 HTTP 요청의 취소 함수를 해제하고 다음 요청에 이전 컨텍스트를 남기지 않는다")
    void clearHooksAndContextAfterCompletion(String selectedProvider) {
        StoryboardGenerator generator = generator(selectedProvider);
        StoryboardAttemptContext context = context();

        assertThat(generator.generate(request.withAttemptContext(context)).storyboard().panels()).hasSize(4);
        context.cancel(CancellationReason.TIMEOUT);

        assertThat(calls.getFirst().isCanceled()).isFalse();
        assertThat(generator.generate(request).storyboard().panels()).hasSize(4);
        assertThat(requests).hasValue(2);
        assertThat(calls.getLast().isCanceled()).isFalse();
    }

    @Test
    @DisplayName("Qwen 완료 신호 뒤 서버가 스트림을 열어 둬도 답변과 사용량을 반환하고 취소 등록을 해제한다")
    void completeQwenStreamWithoutWaitingForServerToClose() throws Exception {
        StoryboardGenerator generator = generator("qwen");
        String usageEvent = "data: " + mapper.writeValueAsString(Map.of("choices", List.of(), "usage", Map.of(
                "prompt_tokens", 100, "completion_tokens", 500, "total_tokens", 600,
                "completion_tokens_details", Map.of("reasoning_tokens", 110)))) + "\n\n";
        byte[] stream = new String(successBody(), StandardCharsets.UTF_8)
                .replace("data: [DONE]", usageEvent + "data: [DONE]").getBytes(StandardCharsets.UTF_8);
        behavior = exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(stream);
            exchange.getResponseBody().flush();
            bodySent.countDown();
            await(release);
        };
        StoryboardAttemptContext context = context();
        var result = workers.submit(() -> generator.generate(request.withAttemptContext(context)));
        assertThat(bodySent.await(3, TimeUnit.SECONDS)).isTrue();

        var completed = result.get(2, TimeUnit.SECONDS);

        assertThat(release.getCount()).isEqualTo(1);
        assertThat(completed.storyboard().panels()).hasSize(4);
        assertThat(completed.tokenUsage()).isEqualTo(new GenerationTokenUsage(100, 390, 110, 600));
        assertThat(requests).hasValue(1);
        assertThat(calls).hasSize(1);
        context.cancel(CancellationReason.TIMEOUT);
        assertThat(calls.getFirst().isCanceled()).isFalse();
    }

    private StoryboardGenerator generator(String selectedProvider) {
        provider = selectedProvider;
        var registry = new SimpleMeterRegistry();
        var logger = new ExternalApiLogger();
        if ("gemini".equals(provider)) {
            var scope = new GeminiStoryboardCallScope();
            OkHttpClient http = SingleAttemptHttpTransport.builder(HTTP_TIMEOUT).eventListenerFactory(call -> {
                calls.add(call);
                return scope.create(call);
            }).build();
            httpClients.add(http);
            HttpOptions options = HttpOptions.builder().baseUrl(url()).apiVersion("v1")
                    .timeout(10_000)
                    .retryOptions(HttpRetryOptions.builder().attempts(1).httpStatusCodes(List.of()).build()).build();
            Client client = new ExpressGeminiClientFactory("fixture-api-key").create(options,
                    ClientOptions.builder().customHttpClient(http).build());
            sdkClients.add(client);
            var properties = new GeminiGenerationProperties("fixture-model", "fixture-image", "high", "1:1",
                    8192, 3, Duration.ofSeconds(180));
            return new GeminiStoryboardGenerator(client.models, properties, mapper, new StoryboardResponseMapper(),
                    new GeminiFailureReporter(new GeminiExceptionTranslator(), logger),
                    new GeminiStageMetrics(registry), scope);
        }
        OkHttpClient http = QwenChatClient.httpClient(HTTP_TIMEOUT).newBuilder().eventListenerFactory(call -> {
            calls.add(call);
            return EventListener.NONE;
        }).build();
        httpClients.add(http);
        // Route only this test's HTTPS API request to the local HTTP server; production validation stays unchanged.
        Call.Factory localCalls = apiRequest -> http.newCall(apiRequest.newBuilder().url(url()).build());
        QwenChatClient client = new QwenChatClient(localCalls, mapper, "sk-sp-fixture-key");
        var settings = QwenStoryboardSettings.defaults();
        return new QwenStoryboardGenerator(client, settings,
                new StoryboardJsonCodec(mapper, new StoryboardResponseMapper()), logger,
                new QwenStageMetrics(registry, settings.model()));
    }

    private void delayResponse(HttpExchange exchange, boolean bodyStarted) throws IOException {
        if (bodyStarted) {
            byte[] body = successBody();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body, 0, body.length / 2);
            exchange.getResponseBody().flush();
            bodySent.countDown();
            await(release);
            exchange.getResponseBody().write(body, body.length / 2, body.length - body.length / 2);
        } else {
            await(release);
            respondSuccess(exchange);
        }
    }

    private void respondSuccess(HttpExchange exchange) throws IOException {
        respond(exchange, 200, successBody());
    }

    private byte[] successBody() {
        String storyboard = StoryboardTestFixtures.storyboardJson();
        if ("gemini".equals(provider)) {
            return mapper.writeValueAsBytes(Map.of("candidates", List.of(Map.of("content",
                    Map.of("role", "model", "parts", List.of(Map.of("text", storyboard))), "finishReason", "STOP"))));
        }
        String stream = "data: " + mapper.writeValueAsString(Map.of("choices", List.of(Map.of(
                "delta", Map.of("content", storyboard))))) + "\n\n"
                + "data: " + mapper.writeValueAsString(Map.of("choices", List.of(Map.of(
                "delta", Map.of(), "finish_reason", "stop")))) + "\n\ndata: [DONE]\n\n";
        return stream.getBytes(StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static StoryboardAttemptContext context() {
        return new StoryboardAttemptContext(System.nanoTime() + HTTP_TIMEOUT.toNanos(), System::nanoTime);
    }

    private static AiGenerationErrorType httpFailureType(int status) {
        if (status == 429) {
            return AiGenerationErrorType.RATE_LIMITED;
        }
        return status == 408 || status == 504 ? AiGenerationErrorType.TIMEOUT : AiGenerationErrorType.PROVIDER_ERROR;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
