package com.harudle.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

class ApiCorsConfigurationTest {

    private static final List<String> ALLOWED_ORIGINS = List.of(
            "https://www.harudle.com",
            "https://harudle.com"
    );

    @Test
    void allowsProductionOriginsAndHeadRequests() {
        AuthProperties authProperties = new AuthProperties(
                ALLOWED_ORIGINS,
                null,
                null,
                null,
                null
        );
        CorsConfigurationSource source = new ApiCorsConfiguration()
                .corsConfigurationSource(authProperties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/csrf");

        CorsConfiguration configuration = source.getCorsConfiguration(request);

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactlyElementsOf(ALLOWED_ORIGINS);
        assertThat(configuration.getAllowedMethods()).contains("GET", "HEAD", "OPTIONS", "PATCH");
    }

    @Test
    void bindsCommaSeparatedOrigins() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.auth.frontend-origins", String.join(",", ALLOWED_ORIGINS));

        AuthProperties properties = Binder.get(environment)
                .bind("app.auth", Bindable.of(AuthProperties.class))
                .get();

        assertThat(properties.frontendOrigins()).containsExactlyElementsOf(ALLOWED_ORIGINS);
    }
}
