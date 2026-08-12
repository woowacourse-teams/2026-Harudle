package com.harudle.common.security;

import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
public class ApiCorsConfiguration {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(AuthProperties authProperties) {
        String frontendOrigin = extractFrontendOrigin(authProperties);
        CorsConfiguration configuration = createConfiguration(frontendOrigin);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);

        return source;
    }

    private CorsConfiguration createConfiguration(String frontendOrigin) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(frontendOrigin));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Accept",
                "Authorization",
                "Content-Type",
                "X-XSRF-TOKEN"
        ));
        configuration.setAllowCredentials(true);

        return configuration;
    }

    private String extractFrontendOrigin(AuthProperties authProperties) {
        Objects.requireNonNull(authProperties, "authProperties는 필수입니다.");

        String frontendOrigin = authProperties.frontendOrigin();
        if (frontendOrigin != null && !frontendOrigin.isBlank()) {
            return frontendOrigin;
        }

        throw new IllegalArgumentException("frontendOrigin은 필수입니다.");
    }
}
