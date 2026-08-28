package com.harudle.common.security;

import com.harudle.common.error.ErrorType;
import com.harudle.common.error.ProblemDetailResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String NO_STORE = "no-store";

    private final BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();
    private final ProblemDetailResponseWriter problemDetailResponseWriter;

    public ApiAuthenticationEntryPoint(ProblemDetailResponseWriter problemDetailResponseWriter) {
        this.problemDetailResponseWriter = Objects.requireNonNull(
                problemDetailResponseWriter,
                "problemDetailResponseWriter는 필수입니다."
        );
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        delegate.commence(request, response, exception);
        response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
        problemDetailResponseWriter.write(request, response, ErrorType.UNAUTHORIZED);
    }
}
