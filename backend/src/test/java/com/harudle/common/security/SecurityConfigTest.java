package com.harudle.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.auth.application.AccessTokenService;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "HARUDLE_API_DOCUMENTATION_ENABLED=true")
@AutoConfigureMockMvc
@Import(SecurityTestController.class)
@TestPropertySource(properties = {
        "app.auth.failure-redirect=http://localhost:5173/auth/callback?error=oauth_failed"
})
class SecurityConfigTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Access Token 없이 보호 API에 접근할 수 없다")
    void rejectsUnauthenticatedApiRequest() throws Exception {
        mockMvc.perform(get("/api/v1/test-auth"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.type").value("urn:harudle:problem:unauthorized"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("DB에 존재하지 않는 사용자는 관리자 API에 접근할 수 없다")
    void rejectsUnknownUserFromAdminApi() throws Exception {
        mockMvc.perform(get("/api/v1/admin/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueAccessToken(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("인증된 일반 사용자는 관리자 API에 접근할 수 없다")
    void rejectsRegularUserFromAdminApi() throws Exception {
        User user = saveUser("security-user@harudle.example", "일반 사용자");

        mockMvc.perform(get("/api/v1/admin/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueAccessToken(user.getId())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("인증된 관리자는 관리자 API에 접근할 수 있다")
    void acceptsAdminApiRequestForAdminUser() throws Exception {
        User admin = saveUser("security-admin@harudle.example", "관리자");
        grantAdminRole(admin);

        mockMvc.perform(get("/api/v1/admin/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueAccessToken(admin.getId())))
                .andExpect(status().isOk())
                .andExpect(content().string("admin"));
    }

    @Test
    @DisplayName("JSESSIONID만으로 보호 API에 접근할 수 없다")
    void rejectsSessionOnlyApiRequest() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("oauth2-authorization-request", "temporary-state");

        mockMvc.perform(get("/api/v1/test-auth").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("유효한 Access Token으로 보호 API에 접근할 수 있다")
    void acceptsValidAccessToken() throws Exception {
        String accessToken = issueAccessToken(UUID.randomUUID());

        MvcResult result = mockMvc.perform(get("/api/v1/test-auth")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(content().string("authenticated"))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    @DisplayName("잘못된 Access Token으로 보호 API에 접근할 수 없다")
    void rejectsInvalidAccessToken() throws Exception {
        mockMvc.perform(get("/api/v1/test-auth")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("공개 API는 Access Token 없이 접근할 수 있다")
    void allowsPublicApi() throws Exception {
        mockMvc.perform(get("/api/v1/public/test"))
                .andExpect(status().isOk())
                .andExpect(content().string("public"));
    }

    @Test
    @DisplayName("이미지 로드 실패 집계는 Access Token이 필요하다")
    void protectsImageLoadFailureTelemetry() throws Exception {
        String path = "/api/v1/telemetry/image-load-failures/timeline";
        Cookie csrfCookie = new Cookie("XSRF-TOKEN", "test-csrf-token");

        mockMvc.perform(post(path)
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(status().isUnauthorized());

        String accessToken = issueAccessToken(UUID.randomUUID());
        mockMvc.perform(post(path)
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("내부 Prometheus 수집 경로만 인증 없이 조회할 수 있다")
    void exposesOnlyPrometheusActuatorEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory_used_bytes")));

        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/env")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueAccessToken(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Scalar 문서는 Access Token 없이 접근할 수 있다")
    void allowsScalarWithoutAccessToken() throws Exception {
        mockMvc.perform(get("/scalar"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("OpenAPI 문서는 Access Token 없이 접근할 수 있다")
    void allowsOpenApiDocsWithoutAccessToken() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("OpenAPI 문서에 CSRF 입력 방식과 변경 API의 성공 응답을 명시한다")
    void documentsCsrfAndMutationSuccessResponses() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.csrfToken.type").value("apiKey"))
                .andExpect(jsonPath("$.components.securitySchemes.csrfToken.in").value("header"))
                .andExpect(jsonPath("$.components.securitySchemes.csrfToken.name").value("X-XSRF-TOKEN"))
                .andExpect(jsonPath("$.paths['/api/v1/diaries'].post.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/diaries'].post.security[0].csrfToken").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/guest/session'].post.security[0].csrfToken").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/telemetry/image-load-failures/timeline'].post"
                        + ".security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/diaries'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/diaries'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/guest/diaries'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/guest/diaries'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/diaries/{diaryId}'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/feeds'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/feeds'].post.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/feeds'].post.security[0].csrfToken").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/feeds/{feedId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/diaries/{diaryId}/share-link']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/public/shares/{shareId}']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/auth/logout'].post.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/guest/session'].post.responses['204']").exists());
    }

    @Test
    @DisplayName("OpenAPI 문서에 API별 Problem Details 코드와 예시를 명시한다")
    void documentsApiErrorResponses() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.HarudleProblemDetail.properties.code.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.HarudleProblemDetail.properties.traceId.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.HarudleProblemDetail.properties.errors.items['$ref']")
                        .value("#/components/schemas/HarudleFieldValidationError"))
                .andExpect(jsonPath("$.paths['/api/v1/diaries'].post.responses['200'].description")
                        .value("멱등 재요청의 기존 일기 반환"))
                .andExpect(jsonPath("$.paths['/api/v1/diaries'].post.responses['400'].description")
                        .value(startsWith("잘못된 요청\n\n| 오류 코드 | 예시 메시지 |")))
                .andExpect(jsonPath("$.paths['/api/v1/diaries'].post.responses['400'].description")
                        .value(containsString("| `VALIDATION_ERROR` | 요청 값이 올바르지 않습니다. |")))
                .andExpect(jsonPath("$.paths['/api/v1/diaries'].post.responses['400'].description")
                        .value(containsString("| `INVALID_IDEMPOTENCY_KEY` | Idempotency-Key는 UUID 형식의 필수 헤더입니다. |")))
                .andExpect(jsonPath("$.paths['/api/v1/diaries'].post.responses['401'].description")
                        .value(startsWith("인증 정보가 없거나 유효하지 않음")))
                .andExpect(jsonPath("$.paths['/api/v1/diaries'].post.responses['409'].description")
                        .value(startsWith("요청이 현재 상태와 충돌함")))
                .andExpect(jsonPath("$.paths['/api/v1/diaries'].post.responses['409'].description")
                        .value(containsString("| `GENERATION_IN_PROGRESS` | 동일한 만화 생성 요청이 처리 중입니다. |")))
                .andExpect(jsonPath("$.paths['/api/v1/diaries'].post.responses['500'].description")
                        .value(startsWith("서버 내부 오류")))
                .andExpect(jsonPath("$.paths['/api/v1/admin/generations/restore-image/upload'].post"
                        + ".responses['413'].description")
                        .value(startsWith("요청 본문 크기 초과")))
                .andExpect(jsonPath("$.paths['/api/v1/diaries'].post.responses['429'].description")
                        .value(startsWith("오늘 이미지 생성 한도 초과")))
                .andExpect(jsonPath("$.paths['/api/v1/diaries'].post.responses['400'].content"
                        + "['application/problem+json'].schema['$ref']")
                        .value("#/components/schemas/HarudleProblemDetail"))
                .andExpect(jsonPath("$.paths['/api/v1/diaries'].post.responses['400'].content"
                        + "['application/problem+json'].examples.VALIDATION_ERROR.value.code")
                        .value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.paths['/api/v1/diaries'].post.responses['400'].content"
                        + "['application/problem+json'].examples.INVALID_IDEMPOTENCY_KEY.value.code")
                        .value("INVALID_IDEMPOTENCY_KEY"))
                .andExpect(jsonPath("$.paths['/api/v1/diaries'].post.responses['429'].headers"
                        + "['Retry-After'].schema.type").value("integer"))
                .andExpect(jsonPath("$.paths['/api/v1/diaries'].post.responses['500'].content"
                        + "['application/problem+json'].examples.INTERNAL_SERVER_ERROR.value.code")
                        .value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/csrf'].get.responses['500'].content"
                        + "['application/problem+json'].examples.INTERNAL_SERVER_ERROR.value.code")
                        .value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.paths['/api/v1/admin/generations/restore-image/upload'].post"
                        + ".responses['413'].content['application/problem+json']"
                        + ".examples['이미지 크기 초과'].value.code")
                        .value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.paths['/api/v1/admin/generations/restore-image/upload'].post"
                        + ".responses['503'].content['application/problem+json']"
                        + ".examples['복구 작업 대기 중단'].value.code")
                        .value("HTTP_503"))
                .andExpect(jsonPath("$.paths['/api/v1/diaries/{diaryId}'].delete.responses['204']")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/diaries/{diaryId}'].delete.responses['404']")
                        .doesNotExist());
    }

    @Test
    @DisplayName("OpenAPI 날짜·정수 형식과 조회 필터를 한글로 설명한다")
    void documentsFormatsAndQueryParametersInKorean() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.CreateDiaryResponse.properties.diaryDate.description")
                        .value("일기 날짜 (YYYY-MM-DD)"))
                .andExpect(jsonPath("$.components.schemas.CreateDiaryResponse.properties.createdAt.description")
                        .value("일기 생성 시각 (RFC 3339)"))
                .andExpect(jsonPath("$.components.schemas.AdminGenerationHistoryResponse.properties.page.description")
                        .value("32비트 정수"))
                .andExpect(jsonPath("$.paths['/api/v1/admin/generations'].get.parameters"
                        + "[?(@.name == 'from')].description").value(hasItem("KST 기준 생성 요청일 시작일 (포함, YYYY-MM-DD)")))
                .andExpect(jsonPath("$.paths['/api/v1/admin/generations'].get.parameters"
                        + "[?(@.name == 'page')].description").value(hasItem("페이지 번호 (0부터 시작)")));
    }

    @Test
    @DisplayName("등록하지 않은 경로는 접근할 수 없다")
    void rejectsUnregisteredPath() throws Exception {
        mockMvc.perform(get("/unregistered")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueAccessToken(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.type").value("urn:harudle:problem:forbidden"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("OAuth 로그인 시작 요청은 OAuth 체인에서 처리한다")
    void redirectsToKakaoAuthorizationPage() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/kakao"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(
                        HttpHeaders.LOCATION,
                        startsWith("https://kauth.kakao.com/oauth/authorize")
                ))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNotNull();
    }

    @Test
    @DisplayName("OAuth 콜백의 state가 없으면 실패 URL로 리다이렉트한다")
    void redirectsOAuthCallbackFailure() throws Exception {
        mockMvc.perform(get("/login/oauth2/code/kakao")
                        .param("code", "authorization-code"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(
                        HttpHeaders.LOCATION,
                        "http://localhost:5173/auth/callback?error=oauth_failed"
                ));
    }

    private User saveUser(String email, String name) {
        return userRepository.saveAndFlush(new User(email, name, Instant.now()));
    }

    private void grantAdminRole(User user) {
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user.getId());
    }

    private String issueAccessToken(UUID userId) {
        return accessTokenService.issue(
                userId,
                Instant.now()
        ).accessToken();
    }
}
