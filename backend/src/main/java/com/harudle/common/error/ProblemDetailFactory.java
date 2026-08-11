package com.harudle.common.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

@Component
public class ProblemDetailFactory {

    private final RequestTraceId requestTraceId;

    ProblemDetailFactory(RequestTraceId requestTraceId) {
        this.requestTraceId = requestTraceId;
    }

    public ProblemDetail create(ErrorType errorType, HttpServletRequest request) {
        return create(errorType, request, List.of());
    }

    ProblemDetail create(
            ErrorType errorType,
            HttpServletRequest request,
            List<FieldValidationError> errors
    ) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                errorType.status(),
                errorType.detail()
        );
        problemDetail.setType(errorType.problemType());
        problemDetail.setTitle(errorType.title());
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("code", errorType.code());
        problemDetail.setProperty("traceId", requestTraceId.getOrCreate(request));
        if (!errors.isEmpty()) {
            problemDetail.setProperty("errors", errors);
        }
        return problemDetail;
    }

}
