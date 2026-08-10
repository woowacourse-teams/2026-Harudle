package com.harudle.common.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

@Component
public class ProblemDetailFactory {

    private static final String PROBLEM_TYPE_BASE_URL = "https://api.harudle.example/problems/";

    public ProblemDetail create(ErrorType errorType, HttpServletRequest request) {
        return create(errorType, request, List.of());
    }

    public ProblemDetail create(
            ErrorType errorType,
            HttpServletRequest request,
            List<FieldValidationError> errors
    ) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                errorType.getStatus(),
                errorType.getDetail()
        );
        problemDetail.setType(URI.create(PROBLEM_TYPE_BASE_URL + errorType.getTypeSlug()));
        problemDetail.setTitle(errorType.getTitle());
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("code", errorType.getCode());
        problemDetail.setProperty("traceId", getTraceId(request));
        if (!errors.isEmpty()) {
            problemDetail.setProperty("errors", errors);
        }
        return problemDetail;
    }

    private String getTraceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        if (traceId instanceof String value) {
            return value;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
