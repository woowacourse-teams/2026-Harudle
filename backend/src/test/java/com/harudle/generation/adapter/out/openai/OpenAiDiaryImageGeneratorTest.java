package com.harudle.generation.adapter.out.openai;

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

class OpenAiDiaryImageGeneratorTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a0f8AAAAASUVORK5CYII=");
    private static final String TOKEN = "test-openai-api-key";
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
        server.createContext("/v1/images/edits", exchange -> {
            calls.incrementAndGet();
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] response = imageResponse();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
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
        var generator = new FallbackDiaryImageGenerator(gemini, openai(), metrics);

        var result = generator.generate(input);

        assertThat(result.resource().getContentAsByteArray()).isEqualTo(PNG);
        assertThat(result.mediaType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(calls).hasValue(1);
        verify(models).generateContent(anyString(), any(Content.class), any(GenerateContentConfig.class));
        assertThat(auth.get()).isEqualTo("Bearer " + TOKEN);
        var json = mapper.readTree(received.get());
        assertThat(json.path("prompt").asText()).endsWith(geminiContent.get().parts().orElseThrow().get(2).text().orElseThrow());
        assertThat(json.path("model").asText()).isEqualTo("gpt-image-2.5-flare");
        assertThat(json.path("prompt").asText()).contains(input.imageStylePromptText(),
                DiaryImagePrompt.finalTask(input, renderer), "style instructions above");
        assertThat(json.path("images").get(0).path("image_url").asText())
                .isEqualTo("data:image/png;base64," + Base64.getEncoder().encodeToString(
                        input.referenceImage().resource().getContentAsByteArray()));
        assertThat(json.path("size").asText()).isEqualTo("1024x1024");
        assertThat(json.path("quality").asText()).isEqualTo("medium");
        assertThat(json.path("n").asInt()).isEqualTo(1);
        assertThat(json.path("output_format").asText()).isEqualTo("png");
    }

    @ParameterizedTest
    @CsvSource({"429,RATE_LIMITED", "401,PROVIDER_ERROR", "422,PROVIDER_ERROR", "503,PROVIDER_ERROR", "504,TIMEOUT"})
    void providerFailuresAreMappedAndNeverRetried(int status, AiGenerationErrorType expected) throws Exception {
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
        assertThatThrownBy(() -> openai().generate(input)).isInstanceOfSatisfying(AiGenerationException.class,
                failure -> assertThat(failure.errorType()).isEqualTo(expected));
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsSuccessfulHttpResponseWithoutAnImage() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 3);
            exchange.getResponseBody().write(new byte[]{1, 2, 3});
            exchange.close();
        });
        server.start();
        assertThatThrownBy(() -> openai().generate(input)).isInstanceOfSatisfying(AiGenerationException.class,
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
        var fallbackProperties = new ImageProviderProperties("openai", TOKEN, null, Duration.ofSeconds(2), null, null);
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
    void geminiTransportTimeoutInvokesOpenAiOnce() throws Exception {
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
        var fallbackProperties = new ImageProviderProperties("openai", TOKEN, null, Duration.ofSeconds(2), null, null);
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
            exchange.getResponseHeaders().set("Content-Type", "application/json");
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
            assertThatThrownBy(() -> openai().generate(input)).isInstanceOfSatisfying(AiGenerationException.class,
                    failure -> assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.TIMEOUT));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(6));
        } finally {
            release.countDown();
        }
    }

    @Test
    void unexpectedTransportExceptionIsMappedAtTheProviderBoundary() throws Exception {
        var calls = mock(okhttp3.Call.Factory.class);
        var call = mock(okhttp3.Call.class);
        var cause = new IllegalStateException("sensitive provider diagnostics");
        when(calls.newCall(any(okhttp3.Request.class))).thenReturn(call);
        when(call.execute()).thenThrow(cause);

        assertThatThrownBy(() -> adapter(calls, metrics).generate(input))
                .isInstanceOfSatisfying(AiGenerationException.class, failure -> {
                    assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
                    assertThat(failure.providerFailureType()).isEqualTo(AiProviderFailureType.UNKNOWN);
                    assertThat(failure).hasCause(cause).hasMessageNotContaining("sensitive");
                });
    }

    @Test
    void unexpectedResponseExceptionIsMappedAsResponseProcessingFailure() throws Exception {
        var calls = mock(okhttp3.Call.Factory.class);
        var call = mock(okhttp3.Call.class);
        var body = mock(okhttp3.ResponseBody.class);
        when(body.source()).thenReturn(new okio.Buffer());
        when(body.contentType()).thenThrow(new IllegalStateException("invalid response"));
        when(calls.newCall(any(okhttp3.Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(new okhttp3.Response.Builder()
                .request(new okhttp3.Request.Builder().url("http://localhost").build())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").body(body).build());

        assertThatThrownBy(() -> adapter(calls, metrics).generate(input))
                .isInstanceOfSatisfying(AiGenerationException.class,
                        failure -> assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.RESPONSE_PROCESSING_ERROR));
    }

    @Test
    void localReferenceReadFailureIsInternalAndDoesNotCallProvider() {
        var calls = mock(okhttp3.Call.Factory.class);
        var reference = new ByteArrayResource(new byte[]{1}) {
            @Override public java.io.InputStream getInputStream() {
                throw new IllegalStateException("local reference failure");
            }
        };
        var request = new DiaryImageGenerationRequest(input.storyboard(), input.imageStylePromptText(),
                new ReferenceImage(reference, MediaType.IMAGE_PNG));

        assertThatThrownBy(() -> adapter(calls, metrics).generate(request))
                .isInstanceOfSatisfying(AiGenerationException.class,
                        failure -> assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.INTERNAL_ERROR));
        verifyNoInteractions(calls);
    }

    @Test
    void classifiedProviderFailureIsPreservedEvenWhenMetricsFail() throws Exception {
        var calls = mock(okhttp3.Call.Factory.class);
        var call = mock(okhttp3.Call.class);
        var original = new AiGenerationException(AiGenerationErrorType.TIMEOUT, "already classified");
        var failingMetrics = mock(io.micrometer.core.instrument.MeterRegistry.class);
        when(failingMetrics.timer(anyString(), any(String[].class))).thenThrow(new IllegalStateException("metrics failed"));
        when(calls.newCall(any(okhttp3.Request.class))).thenReturn(call);
        when(call.execute()).thenThrow(original);

        assertThatThrownBy(() -> adapter(calls, failingMetrics).generate(input)).isSameAs(original);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{}", "{\"data\":[]}", "{\"data\":[{\"b64_json\":\"\"}]}",
            "{\"data\":[{\"b64_json\":\"invalid-base64!\"}]}",
            "{\"data\":[{\"b64_json\":\"AQID\"}]}", "not-json",
            "{\"data\":[{\"b64_json\":\"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwC\"}]}",
            "{\"data\":[{\"b64_json\":\"iVBORw0KGgoAAAANSUhEUgABhqAAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a0f8AAAAASUVORK5CYII=\"}]}"
    })
    void rejectsMissingOrMalformedImageData(String json) throws Exception {
        var factory = mock(okhttp3.Call.Factory.class);
        var call = mock(okhttp3.Call.class);
        when(factory.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(new okhttp3.Response.Builder()
                .request(new okhttp3.Request.Builder().url("https://api.openai.com/v1/images/edits").build())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(okhttp3.ResponseBody.create(json, okhttp3.MediaType.get("application/json"))).build());

        assertThatThrownBy(() -> adapter(factory, metrics).generate(input))
                .isInstanceOfSatisfying(AiGenerationException.class,
                        failure -> assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.RESPONSE_PROCESSING_ERROR));
    }

    @Test
    void sendsOnlyToOfficialEndpointAndRejectsOversizedPromptBeforeHttp() throws Exception {
        var factory = mock(okhttp3.Call.Factory.class);
        var call = mock(okhttp3.Call.class);
        when(factory.newCall(any())).thenReturn(call);
        when(call.execute()).thenThrow(new java.io.IOException("test stop"));
        assertThatThrownBy(() -> adapter(factory, metrics).generate(input)).isInstanceOf(AiGenerationException.class);
        var capture = org.mockito.ArgumentCaptor.forClass(okhttp3.Request.class);
        verify(factory).newCall(capture.capture());
        assertThat(capture.getValue().url().toString()).isEqualTo("https://api.openai.com/v1/images/edits");

        var untouched = mock(okhttp3.Call.Factory.class);
        var oversized = new DiaryImageGenerationRequest(input.storyboard(), "x".repeat(32001), input.referenceImage());
        assertThatThrownBy(() -> adapter(untouched, metrics).generate(oversized))
                .isInstanceOfSatisfying(AiGenerationException.class,
                        failure -> assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.INTERNAL_ERROR));
        verifyNoInteractions(untouched);
    }

    private OpenAiDiaryImageGenerator adapter(okhttp3.Call.Factory calls,
            io.micrometer.core.instrument.MeterRegistry registry) {
        return new OpenAiDiaryImageGenerator(new ImageProviderProperties("openai", TOKEN, null, Duration.ofSeconds(2), null, null),
                renderer, mapper, registry, calls);
    }

    private OpenAiDiaryImageGenerator openai() {
        return new OpenAiDiaryImageGenerator(new ImageProviderProperties("openai", TOKEN, null, Duration.ofSeconds(2), null, null),
                renderer, mapper, metrics, request ->
                        com.harudle.generation.adapter.out.http.SingleAttemptHttpTransport.builder(Duration.ofSeconds(2))
                                .build().newCall(request.newBuilder().url("http://127.0.0.1:"
                                        + server.getAddress().getPort() + request.url().encodedPath()).build()));
    }
    private static byte[] imageResponse() {
        return ("{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(PNG)
                + "\"}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
