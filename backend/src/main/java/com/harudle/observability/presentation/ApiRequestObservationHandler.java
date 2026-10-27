package com.harudle.observability.presentation;

import com.harudle.observability.RequestOperationTimings;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

@Component
class ApiRequestObservationHandler implements ObservationHandler<ServerRequestObservationContext> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiRequestObservationHandler.class);
    private static final String HEALTH_CHECK_PATH = "/api/v1/feeds/00000000-0000-0000-0000-000000000000";
    private final boolean r2Enabled;

    ApiRequestObservationHandler(@Value("${harudle.generation.storage.r2.enabled:false}") boolean r2Enabled) {
        this.r2Enabled = r2Enabled;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ServerRequestObservationContext;
    }

    @Override
    public void onStart(ServerRequestObservationContext context) {
        HttpServletRequest request = context.getCarrier();
        if (request.getRequestURI().startsWith("/api/")
                && !("GET".equals(request.getMethod()) && HEALTH_CHECK_PATH.equals(request.getRequestURI()))) {
            context.put(ApiRequestObservationHandler.class, System.nanoTime());
            RequestOperationTimings.start(request);
        }
    }

    @Override
    public void onStop(ServerRequestObservationContext context) {
        if (!(context.remove(ApiRequestObservationHandler.class) instanceof Long startedAt)
                || context.getResponse() == null) {
            return;
        }
        double durationMs = Math.max(0, System.nanoTime() - startedAt) / 1_000_000.0;
        try {
            var event = LOGGER.atInfo()
                    .addKeyValue("event", "api_request_completed")
                    .addKeyValue("apiGroup", apiGroup(context.getCarrier()))
                    .addKeyValue("operation", operation(context))
                    .addKeyValue("httpStatus", context.getResponse().getStatus())
                    .addKeyValue("durationMs", durationMs)
                    .addKeyValue("r2Enabled", r2Enabled);
            RequestOperationTimings.finish(context.getCarrier()).forEach(event::addKeyValue);
            event.log("event=api_request_completed");
        } catch (RuntimeException exception) {
            try {
                LOGGER.warn("event=metrics_recording_failed component=api_request exceptionType={}",
                        exception.getClass().getSimpleName());
            } catch (RuntimeException ignored) {
                // Observation failures must never change the HTTP response.
            }
        }
    }

    private String apiGroup(HttpServletRequest request) {
        if ("POST".equals(request.getMethod())
                && ("/api/v1/diaries".equals(request.getRequestURI())
                || "/api/v1/guest/diaries".equals(request.getRequestURI())
                || "/api/v1/load-test/diaries".equals(request.getRequestURI()))) {
            return "generation";
        }
        return "general";
    }

    private String operation(ServerRequestObservationContext context) {
        if (!"GET".equals(context.getCarrier().getMethod())) {
            return "other";
        }
        if ("/api/v1/feeds".equals(context.getPathPattern())) {
            return "feed_list";
        }
        if ("/api/v1/feeds/{feedId}".equals(context.getPathPattern())) {
            return "feed_detail";
        }
        return "other";
    }
}
