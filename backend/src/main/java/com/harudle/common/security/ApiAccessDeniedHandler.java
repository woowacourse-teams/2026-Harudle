package com.harudle.common.security;

import com.harudle.common.error.ErrorType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;

class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final BearerTokenAccessDeniedHandler bearerTokenAccessDeniedHandler =
            new BearerTokenAccessDeniedHandler();
    private final ApiProblemResponseWriter problemResponseWriter;

    ApiAccessDeniedHandler(ApiProblemResponseWriter problemResponseWriter) {
        this.problemResponseWriter = problemResponseWriter;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        bearerTokenAccessDeniedHandler.handle(request, response, accessDeniedException);
        problemResponseWriter.write(ErrorType.FORBIDDEN, request, response);
    }
}
