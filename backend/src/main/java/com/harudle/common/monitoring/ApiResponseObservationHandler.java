package com.harudle.common.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.observation.ServerRequestObservationContext;

final class ApiResponseObservationHandler implements ObservationHandler<ServerRequestObservationContext> {

    static final String METRIC_NAME = "harudle.api.response";
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiResponseObservationHandler.class);
    private static final Set<String> METHODS = Set.of(
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT");
    private static final Set<String> LOOPBACK_ADDRESSES = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    private final MeterRegistry registry;
    private final String node;
    private final Timer allApis;

    ApiResponseObservationHandler(MeterRegistry registry, String node) {
        if (!node.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalArgumentException("API metrics node must be a bounded deployment identifier");
        }
        this.registry = registry;
        this.node = node;
        this.allApis = timer("ALL", "ALL");
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ServerRequestObservationContext;
    }

    @Override
    public void onStart(ServerRequestObservationContext context) {
        HttpServletRequest request = context.getCarrier();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean api = path.equals("/api") || path.startsWith("/api/");
        boolean healthcheck = LOOPBACK_ADDRESSES.contains(request.getRemoteAddr())
                && "true".equals(request.getHeader("X-Harudle-Healthcheck"));
        if (api && !healthcheck) {
            context.put(ApiResponseObservationHandler.class, Timer.start(registry));
        }
    }

    @Override
    public void onStop(ServerRequestObservationContext context) {
        Timer.Sample sample = (Timer.Sample) context.remove(ApiResponseObservationHandler.class);
        if (sample == null) {
            return;
        }
        try {
            // Both populations receive the original duration, never an average of quantiles.
            long elapsedNanos = sample.stop(allApis);
            String pattern = context.getPathPattern();
            String uri = pattern != null && (pattern.equals("/api") || pattern.startsWith("/api/"))
                    ? pattern : "UNMATCHED";
            String method = context.getCarrier().getMethod();
            timer(METHODS.contains(method) ? method : "OTHER", uri).record(Duration.ofNanos(elapsedNanos));
        } catch (RuntimeException exception) {
            LOGGER.atWarn()
                    .addKeyValue("event", "api_response_metrics_failed")
                    .addKeyValue("exceptionType", exception.getClass().getSimpleName())
                    .log("API response metrics could not be recorded");
        }
    }

    private Timer timer(String method, String uri) {
        return Timer.builder(METRIC_NAME)
                .description("Completed API HTTP response time, excluding internal health probes")
                .tags("node", node, "method", method, "uri", uri)
                .publishPercentiles(0.95, 0.99)
                .publishPercentileHistogram(false)
                .distributionStatisticExpiry(Duration.ofMinutes(5))
                .distributionStatisticBufferLength(1)
                .register(registry);
    }
}
