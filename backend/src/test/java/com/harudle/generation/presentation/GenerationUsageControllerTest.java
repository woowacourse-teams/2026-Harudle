package com.harudle.generation.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.harudle.auth.infrastructure.oauth.OAuthLoginFailureHandler;
import com.harudle.auth.infrastructure.oauth.OAuthLoginSuccessHandler;
import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.common.error.ApiExceptionLoggerTestConfiguration;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.TraceIdConfiguration;
import com.harudle.common.security.CsrfConfiguration;
import com.harudle.common.security.SecurityConfig;
import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.service.GenerationUsageService;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GenerationUsageController.class)
@Import({
        AuthenticatedUserIdResolver.class,
        ApiExceptionLoggerTestConfiguration.class,
        ProblemDetailFactory.class,
        TraceIdConfiguration.class,
        CsrfConfiguration.class,
        SecurityConfig.class,
})
class GenerationUsageControllerTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 8, 6);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GenerationUsageService generationUsageService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private OAuthLoginSuccessHandler oAuthLoginSuccessHandler;

    @MockitoBean
    private OAuthLoginFailureHandler oAuthLoginFailureHandler;

    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.mockMvc(mockMvc);
    }

    @AfterEach
    void tearDown() {
        RestAssuredMockMvc.reset();
    }

    @Test
    @DisplayName("오늘 생성 사용량을 조회한다")
    void getTodayUsage() {
        when(generationUsageService.getTodayUsage(USER_ID))
                .thenReturn(new GenerationUsage(USAGE_DATE, 2, 3));

        MockMvcResponse response = RestAssuredMockMvc.given()
                .postProcessors(user(USER_ID.toString()))
                .get("/api/v1/me/generation-usage");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("usageDate")).isEqualTo("2026-08-06");
        assertThat(response.jsonPath().getInt("usedCount")).isEqualTo(2);
        assertThat(response.jsonPath().getInt("limitCount")).isEqualTo(3);
        assertThat(response.jsonPath().getInt("remainingCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("Bearer 토큰의 subject를 사용자 ID로 사용한다")
    void getTodayUsageWithBearerToken() {
        Jwt jwt = Jwt.withTokenValue("valid-token")
                .header("alg", "none")
                .subject(USER_ID.toString())
                .build();
        when(jwtDecoder.decode("valid-token")).thenReturn(jwt);
        when(generationUsageService.getTodayUsage(USER_ID))
                .thenReturn(new GenerationUsage(USAGE_DATE, 2, 3));

        MockMvcResponse response = RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .get("/api/v1/me/generation-usage");

        assertThat(response.statusCode()).isEqualTo(200);
        verify(generationUsageService).getTodayUsage(USER_ID);
    }

    @Test
    @DisplayName("인증 정보가 없으면 표준 Bearer challenge를 반환한다")
    void rejectUnauthenticatedRequest() {
        MockMvcResponse response = RestAssuredMockMvc.given()
                .get("/api/v1/me/generation-usage");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
    }

    @Test
    @DisplayName("존재하지 않는 API도 공통 Problem Details를 반환한다")
    void rejectUnknownApi() {
        MockMvcResponse response = RestAssuredMockMvc.given()
                .postProcessors(user(USER_ID.toString()))
                .get("/api/v1/unknown");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.jsonPath().getString("type"))
                .isEqualTo("urn:harudle:problem:api-not-found");
        assertThat(response.jsonPath().getString("code")).isEqualTo("API_NOT_FOUND");
        assertThat(response.jsonPath().getString("traceId")).matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("지원하지 않는 HTTP 메서드도 표준 헤더와 공통 Problem Details를 반환한다")
    void rejectUnsupportedMethod() {
        MockMvcResponse response = RestAssuredMockMvc.given()
                .postProcessors(user(USER_ID.toString()), csrf())
                .post("/api/v1/me/generation-usage");

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.getHeader(HttpHeaders.ALLOW)).contains("GET");
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.jsonPath().getString("type"))
                .isEqualTo("urn:harudle:problem:method-not-allowed");
        assertThat(response.jsonPath().getString("code")).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.jsonPath().getString("traceId")).matches("[0-9a-f]{32}");
    }
}
