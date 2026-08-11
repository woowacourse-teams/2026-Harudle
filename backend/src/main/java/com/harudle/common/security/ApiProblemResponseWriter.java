package com.harudle.common.security;

import com.harudle.common.error.ErrorType;
import com.harudle.common.error.ProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ApiProblemResponseWriter {

    private final ProblemDetailFactory problemDetailFactory;
    private final JsonMapper jsonMapper;

    ApiProblemResponseWriter(
            ProblemDetailFactory problemDetailFactory,
            JsonMapper jsonMapper
    ) {
        this.problemDetailFactory = problemDetailFactory;
        this.jsonMapper = jsonMapper;
    }

    public void write(
            ErrorType errorType,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        ProblemDetail problemDetail = problemDetailFactory.create(errorType, request);
        response.setStatus(errorType.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problemDetail);
    }
}
