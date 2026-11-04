package com.harudle.auth.infrastructure.oauth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Component;

@Component
public class GoogleWithdrawalAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final GoogleWithdrawalAuthorizationState state;

    public GoogleWithdrawalAuthorizationRequestResolver(ClientRegistrationRepository registrations,
            GoogleWithdrawalAuthorizationState state) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations);
        this.delegate.setAuthorizationRequestCustomizer(builder -> builder.attributes(attributes -> {
            if ("apple".equals(attributes.get(OAuth2ParameterNames.REGISTRATION_ID))) {
                builder.additionalParameters(parameters -> parameters.put("response_mode", "form_post"));
            }
        }));
        this.state = state;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return bind(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        return bind(request, delegate.resolve(request, registrationId));
    }

    private OAuth2AuthorizationRequest bind(HttpServletRequest request, OAuth2AuthorizationRequest authorization) {
        String requestId = request.getParameter("withdrawal");
        if (authorization == null || requestId == null) {
            return authorization;
        }
        var session = request.getSession(false);
        String registrationId = authorization.getAttribute(OAuth2ParameterNames.REGISTRATION_ID);
        if (!"google".equals(registrationId) || session == null
                || !state.bind(session, requestId, authorization.getState())) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_withdrawal_request"));
        }
        return authorization;
    }
}
