package com.harudle.common.security;

import com.harudle.common.error.ErrorType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final BearerTokenAuthenticationEntryPoint bearerTokenEntryPoint =
            new BearerTokenAuthenticationEntryPoint();
    private final ApiProblemResponseWriter problemResponseWriter;

    ApiAuthenticationEntryPoint(ApiProblemResponseWriter problemResponseWriter) {
        this.problemResponseWriter = problemResponseWriter;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException
    ) throws IOException {
        bearerTokenEntryPoint.commence(request, response, authenticationException);
        problemResponseWriter.write(ErrorType.UNAUTHORIZED, request, response);
    }
}
