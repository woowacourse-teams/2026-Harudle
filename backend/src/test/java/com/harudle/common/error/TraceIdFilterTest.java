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

        assertThat(MDC.get("traceId")).isNull();
    }
}
