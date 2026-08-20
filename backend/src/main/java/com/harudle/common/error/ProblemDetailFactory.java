package com.harudle.common.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatusCode;
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
        problemDetail.setTitle(errorType.title());
        setCommonProperties(problemDetail, errorType.code(), request);
        if (!errors.isEmpty()) {
            problemDetail.setProperty("errors", errors);
        }
        return problemDetail;
    }

    ProblemDetail enrichFrameworkError(
            ProblemDetail problemDetail,
            HttpStatusCode statusCode,
            HttpServletRequest request
    ) {
        Objects.requireNonNull(problemDetail, "Problem Details 본문은 필수입니다.");
        problemDetail.setStatus(statusCode.value());
        setCommonProperties(
                problemDetail,
                FrameworkErrorType.codeFor(statusCode),
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
