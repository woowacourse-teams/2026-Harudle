package com.harudle.generation.adapter.out.gemini.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeminiClientFactoryTest {

    private static final String MODEL = "gemini-nano-banana-2.1";
    private final AtomicReference<CapturedRequest> request = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            request.set(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("x-goog-api-key")
            ));
            byte[] response = """
                    {"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Express Client는 API Key로 프로젝트 없는 Vertex 경로를 호출한다")
    void callExpressWithApiKey() {
        GeminiClientFactory factory = new ExpressGeminiClientFactory("test-api-key");
        try (Client client = factory.create(httpOptions())) {
            assertThat(client.models.generateContent(MODEL, "test prompt", GenerateContentConfig.builder().build()).text())
                    .isEqualTo("ok");
        }

        assertThat(request.get()).isNotNull();
        assertThat(request.get().method()).isEqualTo("POST");
        assertThat(request.get().path()).isEqualTo("/v1/publishers/google/models/" + MODEL + ":generateContent");
        assertThat(request.get().apiKey()).isEqualTo("test-api-key");
        assertThat(request.get().authorization()).isNull();
    }

    @Test
    @DisplayName("Vertex Client는 Bearer 인증으로 프로젝트와 global을 포함한 모델 경로를 호출한다")
    void callVertexWithCredentials() {
        GoogleCredentials credentials = GoogleCredentials.create(
                new AccessToken("test-access-token", Date.from(Instant.now().plusSeconds(3600))));
        GeminiClientFactory factory = new VertexGeminiClientFactory("test-project", "global", credentials);
        try (Client client = factory.create(httpOptions())) {
            assertThat(client.models.generateContent(MODEL, "test prompt", GenerateContentConfig.builder().build()).text())
                    .isEqualTo("ok");
        }

        assertThat(request.get()).isNotNull();
        assertThat(request.get().method()).isEqualTo("POST");
        assertThat(request.get().path()).isEqualTo(
                "/v1/projects/test-project/locations/global/publishers/google/models/" + MODEL + ":generateContent");
        assertThat(request.get().authorization()).isEqualTo("Bearer test-access-token");
        assertThat(request.get().apiKey()).isNull();
    }

    private HttpOptions httpOptions() {
        return HttpOptions.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiVersion("v1")
                .timeout(5000)
                .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                .build();
    }

    private record CapturedRequest(String method, String path, String authorization, String apiKey) {
    }
}
