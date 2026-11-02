package com.harudle.auth.infrastructure.oauth;

import com.harudle.auth.application.WithdrawalService;
import com.harudle.auth.infrastructure.token.RefreshTokenCookieWriter;
import com.harudle.common.security.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GoogleWithdrawalCallbackHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleWithdrawalCallbackHandler.class);
    private final GoogleWithdrawalAuthorizationState state;
    private final OAuth2AuthorizedClientService clients;
    private final WithdrawalService withdrawal;
    private final RefreshTokenCookieWriter cookies;
    private final URI callback;

    public GoogleWithdrawalCallbackHandler(GoogleWithdrawalAuthorizationState state,
            OAuth2AuthorizedClientService clients, WithdrawalService withdrawal,
            RefreshTokenCookieWriter cookies, AuthProperties properties) {
        this.state = state;
        this.clients = clients;
        this.withdrawal = withdrawal;
        this.cookies = cookies;
        this.callback = properties.successRedirect();
    }

    public boolean success(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        var session = request.getSession(false);
        if (session == null) {
            return false;
        }
        var pending = state.consume(session, request.getParameter("state"));
        if (pending.isEmpty()) {
            return false;
        }
        try {
            if (pending.get().expired() || !(authentication instanceof OAuth2AuthenticationToken google)
                    || !"google".equals(google.getAuthorizedClientRegistrationId())) {
                redirect(response, false);
                return true;
            }
            var client = clients.loadAuthorizedClient("google", google.getName());
            if (client == null) {
                redirect(response, false);
                return true;
            }
            withdrawal.completeGoogle(pending.get().userId(), google, client);
            cookies.clear(response);
            redirect(response, true);
        } catch (RuntimeException exception) {
            LOGGER.warn("event=google_withdrawal_failed exceptionType={}", exception.getClass().getSimpleName());
            redirect(response, false);
        } finally {
            SecurityContextHolder.clearContext();
            session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        }
        return true;
    }

    public boolean failure(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var session = request.getSession(false);
        if (session == null || state.consume(session, request.getParameter("state")).isEmpty()) {
            return false;
        }
        redirect(response, false);
        return true;
    }

    private void redirect(HttpServletResponse response, boolean success) throws IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.sendRedirect(UriComponentsBuilder.fromUri(callback)
                .replaceQueryParam("withdrawal", success ? "success" : "failed").build(true).toUriString());
    }
}
