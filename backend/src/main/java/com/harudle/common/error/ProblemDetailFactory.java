package com.harudle.common.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;

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
        problemDetail.setTitle(errorType.title());
        setCommonProperties(problemDetail, errorType.code(), request);
        if (!errors.isEmpty()) {
            problemDetail.setProperty("errors", errors);
        }
        return problemDetail;
    }

    ProblemDetail create(ErrorResponse errorResponse, HttpServletRequest request) {
        ProblemDetail problemDetail = errorResponse.getBody();
        problemDetail.setStatus(errorResponse.getStatusCode().value());
        setCommonProperties(
                problemDetail,
                FrameworkErrorType.codeFor(errorResponse.getStatusCode()),
                request
        );
        return problemDetail;
    }

    private void setCommonProperties(
            ProblemDetail problemDetail,
            String code,
            HttpServletRequest request
    ) {
        problemDetail.setType(ErrorType.problemType(code));
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("code", code);
        problemDetail.setProperty("traceId", requestTraceId.getOrCreate(request));
    }

}
