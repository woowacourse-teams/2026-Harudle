package com.harudle.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Holds bounded operation summaries on the HTTP request, never on a worker thread. */
public final class RequestOperationTimings {

    private static final String ATTRIBUTE = RequestOperationTimings.class.getName();

    public enum Operation {
        FEED_QUERY("feedQuery"), S3_HEAD("s3Head"), R2_HEAD("r2Head"), S3_SIGN("s3Sign"), R2_SIGN("r2Sign");

        private final String field;

        Operation(String field) {
            this.field = field;
        }
    }

    private final Map<Operation, Summary> operations = new EnumMap<>(Operation.class);
    private int feedImageCount;

    private RequestOperationTimings() {
        for (Operation operation : Operation.values()) {
            operations.put(operation, new Summary());
        }
    }

    public static void start(HttpServletRequest request) {
        try {
            request.setAttribute(ATTRIBUTE, new RequestOperationTimings());
        } catch (RuntimeException ignored) {
            // Request diagnostics must not prevent the request from running.
        }
    }

    public static <T> T measure(Operation operation, Supplier<T> action) {
        long startedAt = System.nanoTime();
        boolean failed = true;
        try {
            T value = action.get();
            failed = false;
            return value;
        } finally {
            record(operation, System.nanoTime() - startedAt, failed);
        }
    }

    public static void record(Operation operation, long elapsedNanos, boolean failed) {
        record(operation, elapsedNanos, failed, false);
    }

    public static void record(Operation operation, long elapsedNanos, boolean failed, boolean missing) {
        RequestOperationTimings timings = current();
        if (timings != null) {
            timings.add(operation, elapsedNanos, failed, missing);
        }
    }

    public static void returnedImages(int count) {
        RequestOperationTimings timings = current();
        if (timings != null) {
            synchronized (timings) {
                timings.feedImageCount += count;
            }
        }
    }

    public static Map<String, Object> finish(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        request.removeAttribute(ATTRIBUTE);
        return value instanceof RequestOperationTimings timings ? timings.fields() : Map.of();
    }

    private static RequestOperationTimings current() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                    && attributes.getRequest().getAttribute(ATTRIBUTE) instanceof RequestOperationTimings timings) {
                return timings;
            }
        } catch (RuntimeException ignored) {
            // A finished or failing request wrapper must not change the storage outcome.
        }
        return null;
    }

    private synchronized void add(Operation operation, long elapsedNanos, boolean failed, boolean missing) {
        Summary summary = operations.get(operation);
        long elapsed = Math.max(0, elapsedNanos);
        summary.count++;
        summary.failed += failed ? 1 : 0;
        summary.missing += missing ? 1 : 0;
        summary.totalNanos += elapsed;
        summary.maxNanos = Math.max(summary.maxNanos, elapsed);
    }

    private synchronized Map<String, Object> fields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("feedImageCount", feedImageCount);
        operations.forEach((operation, summary) -> {
            fields.put(operation.field + "Count", summary.count);
            fields.put(operation.field + "FailureCount", summary.failed);
            fields.put(operation.field + "MissingCount", summary.missing);
            fields.put(operation.field + "DurationMs", summary.totalNanos / 1_000_000.0);
            fields.put(operation.field + "MaxMs", summary.maxNanos / 1_000_000.0);
        });
        return fields;
    }

    private static final class Summary {
        private int count;
        private int failed;
        private int missing;
        private long totalNanos;
        private long maxNanos;
    }
}
