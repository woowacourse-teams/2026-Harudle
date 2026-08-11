package com.harudle.common.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

final class RequestTraceId {

    private static final String ATTRIBUTE = RequestTraceId.class.getName() + ".value";

    private RequestTraceId() {
    }

    static String getOrCreate(HttpServletRequest request) {
        Object attribute = request.getAttribute(ATTRIBUTE);
        if (attribute instanceof String traceId && !traceId.isBlank()) {
            return traceId;
        }

        String traceId = UUID.randomUUID().toString().replace("-", "");
        request.setAttribute(ATTRIBUTE, traceId);
        return traceId;
    }
}
