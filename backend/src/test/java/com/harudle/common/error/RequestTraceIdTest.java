package com.harudle.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RequestTraceIdTest {

    @Test
    @DisplayName("한 요청에서는 같은 trace ID를 생성해 재사용한다")
    void getOrCreateTraceId() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        String created = RequestTraceId.getOrCreate(request);
        String reused = RequestTraceId.getOrCreate(request);

        assertThat(created).matches("[0-9a-f]{32}");
        assertThat(reused).isEqualTo(created);
        assertThat(request.getAttribute(RequestTraceId.ATTRIBUTE)).isEqualTo(created);
    }
}
