package com.harudle.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
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
public class OpenApiConfig {
}
