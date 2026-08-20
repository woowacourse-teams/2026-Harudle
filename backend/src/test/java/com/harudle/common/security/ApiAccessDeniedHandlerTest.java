package com.harudle.common.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.harudle.common.error.ErrorType;
import com.harudle.common.error.ProblemDetailResponseWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;

class ApiAccessDeniedHandlerTest {

    @Test
    @DisplayName("CSRF 검증 실패를 CSRF 오류로 분류한다")
    void handleCsrfFailure() throws Exception {
        ProblemDetailResponseWriter writer = mock(ProblemDetailResponseWriter.class);
        ApiAccessDeniedHandler handler = new ApiAccessDeniedHandler(writer);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new MissingCsrfTokenException(null));

        verify(writer).write(request, response, ErrorType.INVALID_CSRF_TOKEN);
    }

    @Test
    @DisplayName("일반 접근 거부를 권한 오류로 분류한다")
    void handleAccessDenied() throws Exception {
        ProblemDetailResponseWriter writer = mock(ProblemDetailResponseWriter.class);
        ApiAccessDeniedHandler handler = new ApiAccessDeniedHandler(writer);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/unregistered");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("접근할 수 없습니다."));

        verify(writer).write(request, response, ErrorType.FORBIDDEN);
    }
}
