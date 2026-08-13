package com.harudle.auth.infrastructure.oauth;

import com.harudle.common.security.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
public class OAuthLoginFailureHandler implements AuthenticationFailureHandler {

    private static final String NO_STORE = "no-store";

    private final URI failureRedirect;

    public OAuthLoginFailureHandler(AuthProperties authProperties) {
        this.failureRedirect = extractFailureRedirect(authProperties);
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        redirect(response);
    }

    public void redirect(HttpServletResponse response) throws IOException {
        Objects.requireNonNull(response, "response는 필수입니다.");

        response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
        response.sendRedirect(failureRedirect.toString());
    }

    private URI extractFailureRedirect(AuthProperties authProperties) {
        Objects.requireNonNull(authProperties, "authProperties는 필수입니다.");

        return Objects.requireNonNull(
                authProperties.failureRedirect(),
                "failureRedirect 설정은 필수입니다."
        );
    }

}
