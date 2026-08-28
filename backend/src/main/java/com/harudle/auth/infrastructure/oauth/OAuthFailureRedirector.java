package com.harudle.auth.infrastructure.oauth;

import com.harudle.common.security.AuthProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class OAuthFailureRedirector {

    private static final String NO_STORE = "no-store";

    private final URI failureRedirect;

    public OAuthFailureRedirector(AuthProperties authProperties) {
        this.failureRedirect = extractFailureRedirect(authProperties);
    }

    public void redirect(HttpServletResponse response, OAuthFailureReason reason) throws IOException {
        Objects.requireNonNull(response, "response는 필수입니다.");
        Objects.requireNonNull(reason, "OAuth 실패 사유는 필수입니다.");

        response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
        response.sendRedirect(failureRedirect.toString());
    }

    private static URI extractFailureRedirect(AuthProperties authProperties) {
        Objects.requireNonNull(authProperties, "authProperties는 필수입니다.");

        return Objects.requireNonNull(
                authProperties.failureRedirect(),
                "failureRedirect 설정은 필수입니다."
        );
    }
}
