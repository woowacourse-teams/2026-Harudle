package com.harudle.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@ExtendWith(OutputCaptureExtension.class)
class ManagementEndpointIsolationTest {

    @Value("${local.server.port}")
    private int applicationPort;

    @Value("${local.management.port}")
    private int managementPort;

    @Test
    void recordsPublicAndRejectedApiRequestsButNotManagementRequests(CapturedOutput output) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        assertThat(get(client, applicationPort, "/api/v1/feeds?size=1").statusCode()).isEqualTo(200);
        assertThat(get(client, applicationPort, "/api/v1/diaries?year=2026&month=10").statusCode()).isEqualTo(401);
        assertThat(get(client, managementPort, "/actuator/prometheus").statusCode()).isEqualTo(200);

        assertThat(output.getOut().lines().filter(line -> line.contains("event=api_request_completed")))
                .hasSize(2);
    }

    @Test
    void prometheusIsServedOnlyOnTheManagementPort() throws Exception {
        assertThat(managementPort).isNotEqualTo(applicationPort);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> metrics = get(client, managementPort, "/actuator/prometheus");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("jvm_memory_used_bytes");

        assertThat(get(client, applicationPort, "/actuator/prometheus").statusCode())
                .isEqualTo(404);
        assertThat(get(client, managementPort, "/actuator/env").statusCode())
                .isNotEqualTo(200);
    }

    private static HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
