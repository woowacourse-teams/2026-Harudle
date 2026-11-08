package com.harudle.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

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

    @Test
    @DisplayName("허용된 다른 주소의 브라우저가 푸시 상태 번호 응답 헤더를 읽을 수 있다")
    void exposesPushStateVersionToAllowedOrigin() throws Exception {
        AuthProperties properties = new AuthProperties(ALLOWED_ORIGINS, null, null, null, null);
        CorsFilter filter = new CorsFilter(new ApiCorsConfiguration().corsConfigurationSource(properties));
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/me/push-registrations/123");
        request.addHeader("Origin", ALLOWED_ORIGINS.getFirst());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(ALLOWED_ORIGINS.getFirst());
        assertThat(response.getHeader("Access-Control-Expose-Headers")).contains("Push-State-Version");
    }
}
