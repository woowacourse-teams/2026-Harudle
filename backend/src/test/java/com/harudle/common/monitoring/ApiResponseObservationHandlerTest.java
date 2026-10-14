package com.harudle.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.ServerHttpObservationFilter;

class ApiResponseObservationHandlerTest {

    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    private final ApiResponseObservationHandler handler = new ApiResponseObservationHandler(registry, "test-node");

    @Test
    void groupsActualHttpSamplesAcrossStatusCodesWithoutRecordingIdsOrQueryStrings() {
        for (int status : new int[] {200, 500}) {
            var context = context("GET", "/api/v1/diaries/12345");
            ((MockHttpServletRequest) context.getCarrier()).setQueryString("private=secret");
            handler.onStart(context);
            clock.add(Duration.ofMillis(status));
            context.setPathPattern("/api/v1/diaries/{diaryId}");
            context.getResponse().setStatus(status);
            handler.onStop(context);
            handler.onStop(context);
        }
        assertThat(timer("ALL", "ALL").count()).isEqualTo(2);
        assertThat(timer("GET", "/api/v1/diaries/{diaryId}").count()).isEqualTo(2);
        assertThat(timer("ALL", "ALL").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(700);
        assertThat(registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(ApiResponseObservationHandler.METRIC_NAME))
                .toList()).allSatisfy(meter -> {
            assertThat(meter.getId().getTag("uri")).doesNotContain("12345", "secret");
            assertThat(meter.getId().getTags()).extracting(tag -> tag.getKey())
                    .containsExactlyInAnyOrder("node", "method", "uri");
        });
    }

    @Test
    void excludesNonApiRequestsInternalHealthProbesAndGenerationObservations() {
        for (String path : new String[] {"/actuator/prometheus", "/health", "/oauth2/authorization/kakao", "/apiary"}) {
            var context = context("GET", path);
            handler.onStart(context);
            clock.add(Duration.ofSeconds(1));
            handler.onStop(context);
        }
        var healthcheck = context("GET", "/api/v1/feeds/00000000-0000-0000-0000-000000000000");
        ((MockHttpServletRequest) healthcheck.getCarrier()).setRemoteAddr("127.0.0.1");
        ((MockHttpServletRequest) healthcheck.getCarrier()).addHeader("X-Harudle-Healthcheck", "true");
        handler.onStart(healthcheck);
        handler.onStop(healthcheck);
        assertThat(handler.supportsContext(new Observation.Context())).isFalse();
        assertThat(timer("ALL", "ALL").count()).isZero();
        assertThat(registry.getMeters()).noneMatch(meter -> meter.getId().getName().contains("generation"));
    }

    @Test
    void doesNotTrustHealthcheckHeadersFromRemoteClientsAndBoundsUnmatchedRequests() {
        for (String method : new String[] {"INVENTED", "ANOTHER"}) {
            var context = context(method, "/api/private-user-id");
            ((MockHttpServletRequest) context.getCarrier()).setRemoteAddr("192.0.2.1");
            ((MockHttpServletRequest) context.getCarrier()).addHeader("X-Harudle-Healthcheck", "true");
            handler.onStart(context);
            clock.add(Duration.ofMillis(10));
            handler.onStop(context);
        }
        assertThat(timer("OTHER", "UNMATCHED").count()).isEqualTo(2);
        var context = context("GET", "/app/api/v1/diaries");
        ((MockHttpServletRequest) context.getCarrier()).setContextPath("/app");
        handler.onStart(context);
        handler.onStop(context);
        assertThat(timer("ALL", "ALL").count()).isEqualTo(3);
    }

    @Test
    void measuresMvcAndAsyncHttpCompletionExactlyOnce() throws Exception {
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(handler);
        var mvc = MockMvcBuilders.standaloneSetup(new TestController(clock))
                .addFilters(new ServerHttpObservationFilter(observations)).build();
        mvc.perform(get("/api/v1/diaries/12345"));
        var pending = mvc.perform(get("/api/v1/async")).andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult();
        mvc.perform(asyncDispatch(pending));
        assertThat(timer("ALL", "ALL").count()).isEqualTo(2);
        assertThat(timer("GET", "/api/v1/diaries/{diaryId}").count()).isEqualTo(1);
        assertThat(timer("GET", "/api/v1/async").count()).isEqualTo(1);
        assertThat(timer("ALL", "ALL").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(30);
        clock.add(Duration.ofMinutes(2)); // Subsequent background generation is not an HTTP observation.
        assertThat(timer("ALL", "ALL").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(30);
    }

    @Test
    void exportsSummaryQuantilesAndExpiresOldPercentilesAfterIdleWindow() {
        var prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT,
                new io.prometheus.metrics.model.registry.PrometheusRegistry(), clock);
        try {
            var observed = new ApiResponseObservationHandler(prometheus, "test-node");
            var context = context("POST", "/api/v1/diaries");
            observed.onStart(context);
            clock.add(Duration.ofMillis(100));
            context.setPathPattern("/api/v1/diaries");
            observed.onStop(context);
            String scrape = prometheus.scrape();
            assertThat(scrape).contains("# TYPE harudle_api_response_seconds summary", "quantile=\"0.95\"", "quantile=\"0.99\"");
            assertThat(scrape).doesNotContain("harudle_api_response_seconds_bucket");
            var timer = prometheus.get(ApiResponseObservationHandler.METRIC_NAME)
                    .tags("method", "ALL", "uri", "ALL").timer();
            assertThat(timer.takeSnapshot().percentileValues()[0].value(TimeUnit.SECONDS)).isPositive();
            clock.add(Duration.ofMinutes(6));
            assertThat(timer.takeSnapshot().percentileValues()[0].value(TimeUnit.SECONDS)).isZero();
            assertThat(timer.count()).isEqualTo(1);
        } finally {
            prometheus.close();
        }
    }

    @Test
    void bootRegistersTheHandlerAndPreinitializesTheAllApiCounter() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ObservationAutoConfiguration.class))
                .withUserConfiguration(ApiResponseMetricsConfiguration.class)
                .withBean(io.micrometer.core.instrument.MeterRegistry.class, () -> registry)
                .withPropertyValues("harudle.monitoring.api-response.node=boot-node")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    var observations = application.getBean(ObservationRegistry.class);
                    var context = context("GET", "/api/v1/diaries");
                    Observation.createNotStarted("http.server.requests", () -> context, observations).observe(() -> {
                        clock.add(Duration.ofMillis(5));
                        context.setPathPattern("/api/v1/diaries");
                    });
                    assertThat(registry.get(ApiResponseObservationHandler.METRIC_NAME)
                            .tags("node", "boot-node", "method", "ALL", "uri", "ALL").timer().count()).isEqualTo(1);
                });
    }

    @Test
    void recordingFailureDoesNotReplaceTheHttpResult() {
        registry.config().meterFilter(new io.micrometer.core.instrument.config.MeterFilter() {
            @Override
            public io.micrometer.core.instrument.distribution.DistributionStatisticConfig configure(
                    io.micrometer.core.instrument.Meter.Id id,
                    io.micrometer.core.instrument.distribution.DistributionStatisticConfig configuration
            ) {
                if (!"ALL".equals(id.getTag("method"))) {
                    throw new IllegalStateException("private registry error");
                }
                return configuration;
            }
        });
        var context = context("GET", "/api/v1/diaries");
        handler.onStart(context);
        clock.add(Duration.ofMillis(10));
        context.setPathPattern("/api/v1/diaries");
        assertThatCode(() -> handler.onStop(context)).doesNotThrowAnyException();
        assertThat(timer("ALL", "ALL").count()).isEqualTo(1);
    }

    @Test
    void rejectsUnboundedNodeIdentifiers() {
        assertThatThrownBy(() -> new ApiResponseObservationHandler(registry, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Timer timer(String method, String uri) {
        return registry.get(ApiResponseObservationHandler.METRIC_NAME)
                .tags("node", "test-node", "method", method, "uri", uri).timer();
    }

    private static ServerRequestObservationContext context(String method, String path) {
        return new ServerRequestObservationContext(new MockHttpServletRequest(method, path),
                new MockHttpServletResponse());
    }

    @RestController
    static class TestController {
        private final MockClock clock;

        TestController(MockClock clock) {
            this.clock = clock;
        }

        @GetMapping("/api/v1/diaries/{diaryId}")
        String diary(@PathVariable String diaryId) {
            clock.add(Duration.ofMillis(10));
            return "ok";
        }

        @GetMapping("/api/v1/async")
        Callable<String> async() {
            return () -> {
                clock.add(Duration.ofMillis(20));
                return "ok";
            };
        }
    }
}
