package com.harudle.common.security;

import com.harudle.common.error.ErrorType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final AuthenticationEntryPoint bearerTokenEntryPoint;
    private final ApiProblemResponseWriter problemResponseWriter;

    ApiAuthenticationEntryPoint(
            AuthenticationEntryPoint bearerTokenEntryPoint,
            ApiProblemResponseWriter problemResponseWriter
    ) {
        this.bearerTokenEntryPoint = bearerTokenEntryPoint;
        this.problemResponseWriter = problemResponseWriter;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException
    ) throws IOException, ServletException {
        bearerTokenEntryPoint.commence(request, response, authenticationException);
        problemResponseWriter.write(ErrorType.UNAUTHORIZED, request, response);
    }
}
