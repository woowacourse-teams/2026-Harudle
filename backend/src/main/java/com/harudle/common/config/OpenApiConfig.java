package com.harudle.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(
                title = "Harudle API",
                version = "v1",
                description = "하루들 HTTP API 명세"
        ),
        tags = {
                @Tag(name = "Auth", description = "인증 및 사용자"),
                @Tag(name = "Guest", description = "로그인 전 체험"),
                @Tag(name = "Diary", description = "일기 및 히스토리"),
                @Tag(name = "Generation", description = "AI 생성 및 사용량"),
                @Tag(name = "Share", description = "공유 링크")
        }
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
@SecurityScheme(
        name = "csrfToken",
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.HEADER,
        paramName = "X-XSRF-TOKEN",
        description = "먼저 GET /api/v1/auth/csrf 응답의 token 값을 입력합니다. "
                + "함께 발급된 XSRF-TOKEN 쿠키도 요청에 포함되어야 합니다."
)
public class OpenApiConfig {

    private static final String CSRF_SECURITY_SCHEME = "csrfToken";

    @Bean
    OpenApiCustomizer csrfSecurityCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(pathItem ->
                    pathItem.readOperationsMap().forEach((httpMethod, operation) -> {
                        if (requiresCsrf(httpMethod)) {
                            operation.setSecurity(withCsrf(operation));
                        }
                    })
            );
        };
    }

    private static boolean requiresCsrf(PathItem.HttpMethod httpMethod) {
        return switch (httpMethod) {
            case POST, PUT, PATCH, DELETE -> true;
            default -> false;
        };
    }

    private static List<SecurityRequirement> withCsrf(Operation operation) {
        List<SecurityRequirement> securityRequirements = operation.getSecurity();
        if (securityRequirements == null || securityRequirements.isEmpty()) {
            SecurityRequirement csrfRequirement = new SecurityRequirement();
            csrfRequirement.addList(CSRF_SECURITY_SCHEME);
            return List.of(csrfRequirement);
        }

        return securityRequirements.stream()
                .map(securityRequirement -> {
                    SecurityRequirement combinedRequirement = new SecurityRequirement();
                    combinedRequirement.putAll(securityRequirement);
                    combinedRequirement.addList(CSRF_SECURITY_SCHEME);
                    return combinedRequirement;
                })
                .toList();
    }
}
