package com.harudle.auth.infrastructure.oauth;

import com.harudle.common.security.AuthProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

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
        response.sendRedirect(reason == OAuthFailureReason.ACCOUNT_SUSPENDED
                ? suspendedAccountRedirect() : failureRedirect.toString());
    }

    private String suspendedAccountRedirect() {
        UriComponentsBuilder redirect = UriComponentsBuilder.fromUri(failureRedirect);
        redirect.build(true).getQueryParams().keySet().stream()
                .filter(name -> "error".equals(UriUtils.decode(name, StandardCharsets.UTF_8)))
                .toList().forEach(name -> redirect.replaceQueryParam(name));
        return redirect.queryParam("error", "account_suspended").build(true).toUriString();
    }

    private static URI extractFailureRedirect(AuthProperties authProperties) {
        Objects.requireNonNull(authProperties, "authProperties는 필수입니다.");

        return Objects.requireNonNull(
                authProperties.failureRedirect(),
                "failureRedirect 설정은 필수입니다."
        );
    }
}
