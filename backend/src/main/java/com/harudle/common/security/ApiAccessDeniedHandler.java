package com.harudle.common.security;

import com.harudle.common.error.ErrorType;
import com.harudle.common.error.ProblemDetailResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private static final String NO_STORE = "no-store";

    private final BearerTokenAccessDeniedHandler delegate = new BearerTokenAccessDeniedHandler();
    private final ProblemDetailResponseWriter problemDetailResponseWriter;

    public ApiAccessDeniedHandler(ProblemDetailResponseWriter problemDetailResponseWriter) {
        this.problemDetailResponseWriter = Objects.requireNonNull(
                problemDetailResponseWriter,
                "problemDetailResponseWriter는 필수입니다."
        );
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception
    ) throws IOException {
        ErrorType errorType = exception instanceof CsrfException
                ? ErrorType.INVALID_CSRF_TOKEN
                : ErrorType.FORBIDDEN;
        delegate.handle(request, response, exception);
        response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
        problemDetailResponseWriter.write(request, response, errorType);
    }
}
