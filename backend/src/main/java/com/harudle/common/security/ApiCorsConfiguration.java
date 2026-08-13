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
        List<String> frontendOrigins = extractFrontendOrigins(authProperties);
        CorsConfiguration configuration = createConfiguration(frontendOrigins);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);

        return source;
    }

    private CorsConfiguration createConfiguration(List<String> frontendOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(frontendOrigins);
        configuration.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Accept",
                "Authorization",
                "Content-Type",
                "X-XSRF-TOKEN"
        ));
        configuration.setAllowCredentials(true);

        return configuration;
    }

    private List<String> extractFrontendOrigins(AuthProperties authProperties) {
        Objects.requireNonNull(authProperties, "authProperties는 필수입니다.");

        List<String> frontendOrigins = authProperties.frontendOrigins();
        if (frontendOrigins != null
                && !frontendOrigins.isEmpty()
                && frontendOrigins.stream().allMatch(origin -> origin != null && !origin.isBlank())) {
            return List.copyOf(frontendOrigins);
        }

        throw new IllegalArgumentException("frontendOrigins는 비어 있지 않아야 합니다.");
    }
}
