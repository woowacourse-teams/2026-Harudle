package com.harudle.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectMapper;

public class ProblemDetailResponseWriter {

    private final ProblemDetailFactory problemDetailFactory;
    private final ObjectMapper objectMapper;

    public ProblemDetailResponseWriter(
            ProblemDetailFactory problemDetailFactory,
            ObjectMapper objectMapper
    ) {
        this.problemDetailFactory = Objects.requireNonNull(
                problemDetailFactory,
                "problemDetailFactory는 필수입니다."
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper는 필수입니다.");
    }

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorType errorType
    ) throws IOException {
        ProblemDetail problemDetail = problemDetailFactory.create(errorType, request);
        response.setStatus(errorType.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problemDetail);
    }
}
