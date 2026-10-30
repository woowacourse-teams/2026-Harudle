package com.harudle.generation.adapter.out.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.generation.adapter.out.gemini.DiaryImagePromptRenderer;
import com.harudle.generation.adapter.out.http.SingleAttemptHttpTransport;
import com.harudle.generation.config.ImageProviderProperties;
import com.harudle.generation.diary.domain.StoryPanel;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.exception.AiProviderFailureType;
import com.harudle.generation.diary.service.port.dto.DiaryImageGenerationRequest;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

class OpenAiHttpCancellationTest {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final String TOKEN = "test-internal-worker-token-32-characters";
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a0f8AAAAASUVORK5CYII=");
    private final DiaryImageGenerationRequest input = new DiaryImageGenerationRequest(
            new Storyboard("우산 이야기", "Keep protagonist",
                    IntStream.rangeClosed(1, 4).mapToObj(i ->
                            new StoryPanel(i, "캡션 " + i, "scene " + i, "protagonist", "happy", List.of())).toList()),
            "스타일 지시문",
            new ReferenceImage(new ByteArrayResource(new byte[]{1}), MediaType.IMAGE_PNG));
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final CountDownLatch received = new CountDownLatch(1);
    private final CountDownLatch releaseSlow = new CountDownLatch(1);
    private final CountDownLatch releaseHealthy = new CountDownLatch(1);
    private final CountDownLatch transportStopped = new CountDownLatch(1);
    private CountDownLatch bodyReading = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService serverWorkers;
    private ExecutorService callers;
    private OkHttpClient client;
    private HttpHandler behavior;

    @BeforeEach
    void setUp() throws IOException {
        callers = Executors.newFixedThreadPool(2);
        serverWorkers = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverWorkers);
        server.createContext("/", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                received.countDown();
                behavior.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        client = SingleAttemptHttpTransport.builder(HTTP_TIMEOUT)
                .eventListener(new EventListener() {
                    @Override
                    public void responseBodyStart(Call call) {
                        bodyReading.countDown();
                    }

                    @Override
                    public void callFailed(Call call, IOException exception) {
                        transportStopped.countDown();
                    }
                }).build();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        releaseSlow.countDown();
        releaseHealthy.countDown();
        calls.forEach(Call::cancel);
        callers.shutdownNow();
        server.stop(0);
        serverWorkers.shutdownNow();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow();
        assertThat(callers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        assertThat(serverWorkers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        metrics.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("헤더 또는 JSON 본문 대기 중 인터럽트되면 응답을 기다리지 않고 해당 HTTP 요청을 취소한다")
    void interruptCancelsRealHttpCallBeforeTheServerFinishes(boolean bodyStarted) throws Exception {
        behavior = exchange -> delayImage(exchange, bodyStarted, releaseSlow);
        var caller = new AtomicReference<Thread>();
        Future<Failure> result = generateExpectingFailure(adapter(recordingCalls()), caller);
        assertThat(received.await(3, TimeUnit.SECONDS)).isTrue();
        if (bodyStarted) {
            assertThat(bodyReading.await(3, TimeUnit.SECONDS)).isTrue();
        }

        caller.get().interrupt();
        Failure failure = result.get(3, TimeUnit.SECONDS);

        assertCancelled(failure);
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().isCanceled()).isTrue();
        assertThat(transportStopped.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(releaseSlow.getCount()).isEqualTo(1);
        assertThat(metrics.find("harudle.image.provider.duration").tag("outcome", "success").timer()).isNull();
    }

    @Test
    @DisplayName("이미지가 도착하는 순간 인터럽트되어도 성공 이미지로 반환하지 않는다")
    void interruptRacingWithAValidResponseDoesNotReturnSuccess() throws Exception {
        Call.Factory factory = mock(Call.Factory.class);
        Call call = mock(Call.class);
        Thread caller = Thread.currentThread();
        when(factory.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenAnswer(invocation -> {
            caller.interrupt();
            return new Response.Builder().request(new Request.Builder().url("http://localhost").build())
                    .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create(imageResponse(), okhttp3.MediaType.get("application/json"))).build();
        });
        Failure failure;
        try {
            AiGenerationException exception = catchThrowableOfType(
                    () -> adapter(factory).generate(input), AiGenerationException.class);
            failure = new Failure(exception, caller.isInterrupted());
        } finally {
            Thread.interrupted();
        }

        assertCancelled(failure);
        verify(call).cancel();
        assertThat(metrics.find("harudle.image.provider.duration").tag("outcome", "success").timer()).isNull();
    }

    @Test
    @DisplayName("이미 인터럽트된 요청은 HTTP 호출을 시작하지 않는다")
    void anAlreadyInterruptedCallerDoesNotStartHttp() {
        Call.Factory factory = mock(Call.Factory.class);
        Failure failure;
        Thread.currentThread().interrupt();
        try {
            AiGenerationException exception = catchThrowableOfType(
                    () -> adapter(factory).generate(input), AiGenerationException.class);
            failure = new Failure(exception, Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        assertCancelled(failure);
        verifyNoInteractions(factory);
    }

    @Test
    @DisplayName("한 요청을 취소해도 같은 Provider의 다른 요청은 정상 완료한다")
    void cancellingOneCallDoesNotCancelAnotherCall() throws Exception {
        bodyReading = new CountDownLatch(2);
        var requests = new AtomicInteger();
        var slowReceived = new CountDownLatch(1);
        behavior = exchange -> {
            boolean slow = requests.incrementAndGet() == 1;
            if (slow) {
                slowReceived.countDown();
            }
            delayImage(exchange, true, slow ? releaseSlow : releaseHealthy);
        };
        var adapter = adapter(recordingCalls());
        var caller = new AtomicReference<Thread>();
        Future<Failure> cancelled = generateExpectingFailure(adapter, caller);
        assertThat(slowReceived.await(3, TimeUnit.SECONDS)).isTrue();
        Future<GeneratedImage> healthy = callers.submit(() -> adapter.generate(input));
        assertThat(bodyReading.await(3, TimeUnit.SECONDS)).isTrue();

        caller.get().interrupt();
        assertCancelled(cancelled.get(3, TimeUnit.SECONDS));
        assertThat(calls).hasSize(2);
        assertThat(calls.getFirst().isCanceled()).isTrue();
        assertThat(calls.getLast().isCanceled()).isFalse();
        releaseHealthy.countDown();

        assertThat(healthy.get(3, TimeUnit.SECONDS).resource().getContentAsByteArray()).isEqualTo(PNG);
        assertThat(calls.getLast().isCanceled()).isFalse();
    }

    private Future<Failure> generateExpectingFailure(
            OpenAiDiaryImageGenerator adapter, AtomicReference<Thread> caller
    ) {
        return callers.submit(() -> {
            caller.set(Thread.currentThread());
            AiGenerationException failure = catchThrowableOfType(
                    () -> adapter.generate(input), AiGenerationException.class);
            return new Failure(failure, Thread.currentThread().isInterrupted());
        });
    }

    private void assertCancelled(Failure result) {
        assertThat(result.failure()).isNotNull();
        assertThat(result.failure().errorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
        assertThat(result.failure().providerFailureType()).isEqualTo(AiProviderFailureType.CANCELLED);
        assertThat(result.interrupted()).isTrue();
    }

    private Call.Factory recordingCalls() {
        return request -> {
            Call call = client.newCall(request.newBuilder()
                    .url("http://127.0.0.1:" + server.getAddress().getPort()).build());
            calls.add(call);
            return call;
        };
    }

    private OpenAiDiaryImageGenerator adapter(Call.Factory factory) {
        return new OpenAiDiaryImageGenerator(new ImageProviderProperties("openai", TOKEN, null, HTTP_TIMEOUT, null, null),
                new DiaryImagePromptRenderer(), new ObjectMapper(), metrics, factory);
    }

    private void delayImage(HttpExchange exchange, boolean bodyStarted, CountDownLatch release) throws IOException {
        if (bodyStarted) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, imageResponse().length);
            exchange.getResponseBody().write(imageResponse(), 0, 1);
            exchange.getResponseBody().flush();
        }
        try {
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IOException("Test response was not released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!bodyStarted) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, imageResponse().length);
        }
        int offset = bodyStarted ? 1 : 0;
        exchange.getResponseBody().write(imageResponse(), offset, imageResponse().length - offset);
    }

    private record Failure(AiGenerationException failure, boolean interrupted) {
    }
    private static byte[] imageResponse() {
        return ("{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(PNG)
                + "\"}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
