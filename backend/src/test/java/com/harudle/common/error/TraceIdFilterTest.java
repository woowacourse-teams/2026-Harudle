package com.harudle.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    private static final String TRACE_ID = "fixed-trace-id";

    private final TraceIdFilter traceIdFilter = new TraceIdFilter(
            new RequestTraceId(() -> TRACE_ID)
    );
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("요청 처리 중 Trace ID를 MDC에 등록하고 완료 후 제거한다")
    void registerAndRemoveTraceId() throws Exception {
        AtomicReference<String> traceIdDuringRequest = new AtomicReference<>();

        traceIdFilter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) -> traceIdDuringRequest.set(MDC.get("traceId"))
        );

        assertThat(traceIdDuringRequest).hasValue(TRACE_ID);
        assertThat(response.getHeader("X-Trace-Id")).isEqualTo(TRACE_ID);
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("요청 처리 중 예외가 발생해도 MDC의 Trace ID를 제거한다")
    void removeTraceIdWhenRequestFails() {
        assertThatThrownBy(() -> traceIdFilter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) -> {
                    throw new ServletException("요청 처리에 실패했습니다.");
                }
        )).isInstanceOf(ServletException.class);

        assertThat(response.getHeader("X-Trace-Id")).isEqualTo(TRACE_ID);
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("응답에는 클라이언트가 보낸 값 대신 서버가 생성한 Trace ID를 사용한다")
    void responseUsesServerGeneratedTraceId() throws Exception {
        request.addHeader("X-Trace-Id", "client-provided-value");

        traceIdFilter.doFilter(request, response, (servletRequest, servletResponse) -> {
            assertThat(response.getHeader("X-Trace-Id")).isEqualTo(MDC.get("traceId"));
        });

        assertThat(response.getHeader("X-Trace-Id")).isEqualTo(TRACE_ID);
    }
}
