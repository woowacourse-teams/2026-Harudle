package com.harudle.auth.infrastructure.oauth;

import java.time.Clock;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.util.LinkedMultiValueMap;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.auth.apple.enabled", havingValue = "true")
@EnableConfigurationProperties({AppleClientSecretProperties.class, OAuth2ClientProperties.class})
public class AppleOAuthConfiguration {

    @Bean
    public ClientRegistrationRepository appleClientRegistrationRepository(
            OAuth2ClientProperties existingProperties,
            AppleClientSecretProperties apple,
            @Value("${app.auth.apple.redirect-uri}") String redirectUri
    ) {
        var registrations = new ArrayList<>(
                new OAuth2ClientPropertiesMapper(existingProperties).asClientRegistrations().values());
        registrations.add(ClientRegistration.withRegistrationId("apple")
                .clientId(apple.clientId())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scope("openid", "email")
                .authorizationUri("https://appleid.apple.com/auth/authorize")
                .tokenUri("https://appleid.apple.com/auth/token")
                .jwkSetUri("https://appleid.apple.com/auth/keys")
                .issuerUri("https://appleid.apple.com")
                .userNameAttributeName("sub")
                .clientName("Apple")
                .build());
        return new InMemoryClientRegistrationRepository(registrations);
    }

    @Bean
    public AppleClientSecretGenerator appleClientSecretGenerator(
            AppleClientSecretProperties properties,
            @Qualifier("authClock") Clock clock
    ) {
        return new AppleClientSecretGenerator(properties, clock);
    }

    @Bean
    public OAuth2AuthorizationRequestResolver appleAuthorizationRequestResolver(
            ClientRegistrationRepository registrations
    ) {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations);
        resolver.setAuthorizationRequestCustomizer(builder -> builder.attributes(attributes -> {
            if ("apple".equals(attributes.get(OAuth2ParameterNames.REGISTRATION_ID))) {
                builder.additionalParameters(parameters -> parameters.put("response_mode", "form_post"));
            }
        }));
        return resolver;
    }

    @Bean
    public RestClientAuthorizationCodeTokenResponseClient appleTokenResponseClient(
            AppleClientSecretGenerator generator
    ) {
        var client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setParametersConverter(request -> {
            var parameters = new LinkedMultiValueMap<String, String>();
            if ("apple".equals(request.getClientRegistration().getRegistrationId())) {
                parameters.set(OAuth2ParameterNames.CLIENT_SECRET, generator.generate());
            }
            return parameters;
        });
        return client;
    }

}
