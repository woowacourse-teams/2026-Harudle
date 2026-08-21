package com.harudle.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class ProblemDetailResponseWriterTest {

    private static final String TRACE_ID = "fixed-trace-id";

    private final ProblemDetailResponseWriter writer = new ProblemDetailResponseWriter(
            new ProblemDetailFactory(new RequestTraceId(() -> TRACE_ID)),
            new ObjectMapper()
    );

    @Test
    @DisplayName("필터 오류를 공통 Problem Details 응답으로 작성한다")
    void writeProblemDetail() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(request, response, ErrorType.UNAUTHORIZED);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString())
                .contains("\"type\":\"urn:harudle:problem:unauthorized\"")
                .contains("\"status\":401")
                .contains("\"instance\":\"/api/v1/test\"")
                .contains("\"code\":\"UNAUTHORIZED\"")
                .contains("\"traceId\":\"" + TRACE_ID + "\"");
    }
}
