package com.harudle.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RequestTraceIdTest {

    @Test
    @DisplayName("한 요청에서는 같은 trace ID를 생성해 재사용한다")
    void getOrCreateTraceId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AtomicInteger generationCount = new AtomicInteger();
        RequestTraceId requestTraceId = new RequestTraceId(
                () -> "fixed-trace-id-" + generationCount.incrementAndGet()
        );

        String created = requestTraceId.getOrCreate(request);
        String reused = requestTraceId.getOrCreate(request);

        assertThat(created).isEqualTo("fixed-trace-id-1");
        assertThat(reused).isEqualTo(created);
        assertThat(generationCount).hasValue(1);
    }
}
