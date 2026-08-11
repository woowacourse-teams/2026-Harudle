package com.harudle.common.error;

import jakarta.servlet.http.HttpServletRequest;

final class RequestTraceId {

    private static final String ATTRIBUTE = RequestTraceId.class.getName() + ".value";

    private final TraceIdGenerator traceIdGenerator;

    RequestTraceId(TraceIdGenerator traceIdGenerator) {
        this.traceIdGenerator = traceIdGenerator;
    }

    String getOrCreate(HttpServletRequest request) {
        String traceId = (String) request.getAttribute(ATTRIBUTE);
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }

        traceId = traceIdGenerator.generate();
        request.setAttribute(ATTRIBUTE, traceId);
        return traceId;
    }
}
