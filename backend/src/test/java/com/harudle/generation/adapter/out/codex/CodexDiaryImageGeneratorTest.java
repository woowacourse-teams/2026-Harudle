package com.harudle.generation.adapter.out.codex;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.genai.Models;
import com.google.genai.errors.ClientException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.adapter.out.gemini.*;
import com.harudle.generation.adapter.out.image.DiaryImagePrompt;
import com.harudle.generation.config.GeminiGenerationProperties;
import com.harudle.generation.config.ImageProviderProperties;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.domain.StoryPanel;
import com.harudle.generation.diary.service.exception.*;
import com.harudle.generation.diary.service.image.FallbackDiaryImageGenerator;
import com.harudle.generation.diary.service.port.dto.*;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

class CodexDiaryImageGeneratorTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a0f8AAAAASUVORK5CYII=");
    private static final String TOKEN = "test-internal-worker-token-32-characters";
    private final ObjectMapper mapper = new ObjectMapper();
    private final DiaryImagePromptRenderer renderer = new DiaryImagePromptRenderer();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final DiaryImageGenerationRequest input = new DiaryImageGenerationRequest(
            new Storyboard("우산 이야기", "Keep protagonist",
                    IntStream.rangeClosed(1, 4).mapToObj(i ->
                            new StoryPanel(i, "캡션 " + i, "scene " + i, "protagonist", "happy", List.of())).toList()),
            "스타일 지시문\n원문 그대로",
            new ReferenceImage(new ByteArrayResource("exact-reference".getBytes()), MediaType.IMAGE_PNG));
    private HttpServer server;

    @AfterEach
    void closeServer() {
        if (server != null) server.stop(0);
    }

    @ParameterizedTest
    @CsvSource({"429,RESOURCE_EXHAUSTED", "408,DEADLINE_EXCEEDED", "504,DEADLINE_EXCEEDED"})
    void eligibleGeminiFailureUsesTheSamePreparedPromptAndReferenceOverHttp(int status, String providerStatus) throws Exception {
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            calls.incrementAndGet();
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, PNG.length);
            exchange.getResponseBody().write(PNG);
            exchange.close();
        });
        server.start();
        var models = mock(Models.class);
        AtomicReference<Content> geminiContent = new AtomicReference<>();
        when(models.generateContent(anyString(), any(Content.class), any(GenerateContentConfig.class)))
                .thenAnswer(invocation -> {
                    geminiContent.set(invocation.getArgument(1));
                    throw new ClientException(status, providerStatus, "provider failure");
                });
        var gemini = new GeminiDiaryImageGenerator(models,
                new GeminiGenerationProperties("story", "image", "high", "1:1", 8192, 1, Duration.ofSeconds(30)),
                renderer, new GeminiFailureReporter(new GeminiExceptionTranslator(), new ExternalApiLogger()),
                new GeminiStageMetrics(metrics));
        var generator = new FallbackDiaryImageGenerator(gemini, codex(), metrics);

        var result = generator.generate(input);

        assertThat(result.resource().getContentAsByteArray()).isEqualTo(PNG);
        assertThat(result.mediaType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(calls).hasValue(1);
        verify(models).generateContent(anyString(), any(Content.class), any(GenerateContentConfig.class));
        assertThat(auth.get()).isEqualTo("Bearer " + TOKEN);
        var json = mapper.readTree(received.get());
        assertThat(json.get("systemInstruction").asText()).isEqualTo(input.imageStylePromptText());
        var parts = geminiContent.get().parts().orElseThrow();
        assertThat(json.get("referenceInstruction").asText()).isEqualTo(parts.get(0).text().orElseThrow());
        assertThat(Base64.getDecoder().decode(json.get("reference").get("base64").asText()))
                .isEqualTo(parts.get(1).inlineData().orElseThrow().data().orElseThrow());
        assertThat(json.get("reference").get("mediaType").asText()).isEqualTo("image/png");
        assertThat(json.get("finalTask").asText()).isEqualTo(parts.get(2).text().orElseThrow())
                .isEqualTo(DiaryImagePrompt.finalTask(input, renderer));
    }

    @ParameterizedTest
    @CsvSource({"429,RATE_LIMITED", "401,PROVIDER_ERROR", "422,PROVIDER_ERROR", "503,PROVIDER_ERROR", "504,TIMEOUT"})
    void workerFailuresAreMappedAndNeverRetried(int status, AiGenerationErrorType expected) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Retry-After", "0");
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        assertThatThrownBy(() -> codex().generate(input)).isInstanceOfSatisfying(AiGenerationException.class,
                failure -> assertThat(failure.errorType()).isEqualTo(expected));
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsSuccessfulHttpResponseWithoutAnImage() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, 3);
            exchange.getResponseBody().write(new byte[]{1, 2, 3});
            exchange.close();
        });
        server.start();
        assertThatThrownBy(() -> codex().generate(input)).isInstanceOfSatisfying(AiGenerationException.class,
                failure -> assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.RESPONSE_PROCESSING_ERROR));
    }

    @ParameterizedTest
    @CsvSource({"429,RESOURCE_EXHAUSTED", "408,DEADLINE_EXCEEDED", "504,DEADLINE_EXCEEDED"})
    void configuredGeminiSdkDoesNotRetryBeforeFallback(int status, String providerStatus) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] error = """
                    {"error":{"code":%d,"status":"%s","message":"provider failure"}}
                    """.formatted(status, providerStatus).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, error.length);
            exchange.getResponseBody().write(error);
            exchange.close();
        });
        server.start();
        var properties = new GeminiGenerationProperties("story", "image", "high", "1:1", 8192, 3, Duration.ofSeconds(3));
        var fallbackProperties = new ImageProviderProperties("codex",
                URI.create("http://127.0.0.1:8090"), TOKEN, Duration.ofSeconds(2));
        var configuration = new com.harudle.generation.config.GenerationAdapterConfiguration();
        try (var client = configuration.geminiClient(properties, (httpOptions, clientOptions) ->
                com.google.genai.Client.builder().vertexAI(false).apiKey("test-key")
                        .httpOptions(httpOptions.toBuilder()
                                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build())
                        .clientOptions(clientOptions).build(), fallbackProperties)) {
            var gemini = new GeminiDiaryImageGenerator(client.models, properties, renderer,
                    new GeminiFailureReporter(new GeminiExceptionTranslator(), new ExternalApiLogger()),
                    new GeminiStageMetrics(metrics));
            var fallback = mock(com.harudle.generation.diary.service.port.DiaryImageGenerator.class);
            var expected = new GeneratedImage(new ByteArrayResource(PNG), MediaType.IMAGE_PNG);
            when(fallback.generate(input)).thenReturn(expected);

            assertThat(new FallbackDiaryImageGenerator(gemini, fallback, metrics).generate(input))
                    .isSameAs(expected);
            assertThat(calls).hasValue(1);
            verify(fallback).generate(same(input));
        }
    }

    @Test
    void geminiTransportTimeoutInvokesCodexOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var release = new java.util.concurrent.CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        var properties = new GeminiGenerationProperties("story", "image", "high", "1:1", 8192, 3, Duration.ofSeconds(1));
        var fallbackProperties = new ImageProviderProperties("codex",
                URI.create("http://127.0.0.1:8090"), TOKEN, Duration.ofSeconds(2));
        var configuration = new com.harudle.generation.config.GenerationAdapterConfiguration();
        try (var client = configuration.geminiClient(properties, (httpOptions, clientOptions) ->
                com.google.genai.Client.builder().vertexAI(false).apiKey("test-key")
                        .httpOptions(httpOptions.toBuilder()
                                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build())
                        .clientOptions(clientOptions).build(), fallbackProperties)) {
            var gemini = new GeminiDiaryImageGenerator(client.models, properties, renderer,
                    new GeminiFailureReporter(new GeminiExceptionTranslator(), new ExternalApiLogger()),
                    new GeminiStageMetrics(metrics));
            var fallback = mock(com.harudle.generation.diary.service.port.DiaryImageGenerator.class);
            var expected = new GeneratedImage(new ByteArrayResource(PNG), MediaType.IMAGE_PNG);
            when(fallback.generate(input)).thenReturn(expected);

            assertThat(new FallbackDiaryImageGenerator(gemini, fallback, metrics).generate(input)).isSameAs(expected);
            assertThat(calls).hasValue(1);
            verify(fallback).generate(same(input));
            assertThat(metrics.get("harudle.image.fallback.calls").tag("reason", "timeout").counter().count())
                    .isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void httpDeadlineAlsoAppliesAfterResponseHeaders() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, PNG.length);
            exchange.getResponseBody().write(PNG, 0, 1);
            exchange.getResponseBody().flush();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        long started = System.nanoTime();
        try {
            assertThatThrownBy(() -> codex().generate(input)).isInstanceOfSatisfying(AiGenerationException.class,
                    failure -> assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.TIMEOUT));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(6));
        } finally {
            release.countDown();
        }
    }

    private CodexDiaryImageGenerator codex() {
        return new CodexDiaryImageGenerator(new ImageProviderProperties("codex",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), TOKEN, Duration.ofSeconds(2)),
                renderer, mapper, metrics);
    }
}
