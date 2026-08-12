package com.harudle.common.security;

import com.harudle.common.error.ErrorType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final AccessDeniedHandler bearerTokenAccessDeniedHandler;
    private final ApiProblemResponseWriter problemResponseWriter;

    ApiAccessDeniedHandler(
            AccessDeniedHandler bearerTokenAccessDeniedHandler,
            ApiProblemResponseWriter problemResponseWriter
    ) {
        this.bearerTokenAccessDeniedHandler = bearerTokenAccessDeniedHandler;
        this.problemResponseWriter = problemResponseWriter;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException, ServletException {
        bearerTokenAccessDeniedHandler.handle(request, response, accessDeniedException);
        problemResponseWriter.write(ErrorType.FORBIDDEN, request, response);
    }
}
