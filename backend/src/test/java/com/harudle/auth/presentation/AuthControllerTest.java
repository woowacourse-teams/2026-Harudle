package com.harudle.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.auth.application.IssuedRefreshToken;
import com.harudle.auth.application.RefreshTokenService;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.auth.frontend-origins=http://localhost:5173",
        "app.auth.failure-redirect=http://localhost:5173/auth/callback?error=oauth_failed"
})
class AuthControllerTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final String FRONTEND_ORIGIN = "http://localhost:5173";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.mockMvc(mockMvc);
    }

    @AfterEach
    void tearDown() {
        RestAssuredMockMvc.reset();
    }

    @Test
    @DisplayName("CSRF Token을 발급하고 Cookie와 응답 본문으로 전달한다")
    void issuesCsrfToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();

        Cookie csrfCookie = findCurrentCsrfCookie(result);
        List<String> setCookieHeaders = List.copyOf(
                result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)
        );

        assertThat(csrfCookie.isHttpOnly()).isFalse();
        assertThat(csrfCookie.getPath()).isEqualTo("/api/v1");
        assertThat(setCookieHeaders).hasSize(2);
        assertThat(setCookieHeaders.getFirst())
                .startsWith("XSRF-TOKEN=;")
                .contains("Path=/api/v1/auth")
                .contains("Max-Age=0");
        assertThat(setCookieHeaders.getLast())
                .startsWith("XSRF-TOKEN=")
                .contains("Path=/api/v1")
                .doesNotContain("Max-Age=0");
        assertThat(result.getResponse().getContentAsString())
                .contains(csrfCookie.getValue());
    }

    @Test
    @DisplayName("기존 경로와 신규 경로의 CSRF Cookie가 함께 있어도 기존 Cookie를 삭제하고 새 Token을 사용한다")
    void migratesLegacyCsrfCookieWhenDuplicateNamesExist() throws Exception {
        Cookie legacyCsrfCookie = csrfCookie("legacy-token", "/api/v1/auth");
        Cookie currentCsrfCookie = csrfCookie("current-token", "/api/v1");

        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf")
                        .cookie(legacyCsrfCookie, currentCsrfCookie))
                .andExpect(status().isOk())
                .andReturn();

        List<String> setCookieHeaders = List.copyOf(
                result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)
        );
        Cookie migratedCsrfCookie = findCurrentCsrfCookie(result);

        assertThat(setCookieHeaders).hasSize(2);
        assertThat(setCookieHeaders.getFirst())
                .startsWith("XSRF-TOKEN=;")
                .contains("Path=/api/v1/auth")
                .contains("Max-Age=0");
        assertThat(setCookieHeaders.getLast())
                .startsWith("XSRF-TOKEN=")
                .contains("Path=/api/v1")
                .doesNotContain("Max-Age=0");
        assertThat(migratedCsrfCookie.getValue())
                .isNotEqualTo(legacyCsrfCookie.getValue())
                .isNotEqualTo(currentCsrfCookie.getValue());

        IssuedRefreshToken issuedRefreshToken = issueRefreshToken();
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshTokenCookie(issuedRefreshToken), migratedCsrfCookie)
                        .header("X-XSRF-TOKEN", migratedCsrfCookie.getValue()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Refresh 요청에 CSRF Header가 없으면 토큰을 회전하지 않는다")
    void rejectsRefreshWithoutCsrfHeader() throws Exception {
        Cookie csrfCookie = issueCsrfCookie();
        IssuedRefreshToken issuedRefreshToken = issueRefreshToken();

        MockMvcResponse errorResponse = RestAssuredMockMvc.given()
                .cookie("refresh_token", issuedRefreshToken.rawToken())
                .cookie("XSRF-TOKEN", csrfCookie.getValue())
                .post("/api/v1/auth/refresh");

        assertProblemDetails(
                errorResponse,
                403,
                "invalid-csrf-token",
                "Invalid CSRF token",
                "CSRF Token이 유효하지 않습니다.",
                "INVALID_CSRF_TOKEN",
                "/api/v1/auth/refresh"
        );
        assertThat(errorResponse.header(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(errorResponse.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");

        MockMvcResponse successResponse = RestAssuredMockMvc.given()
                .cookie("refresh_token", issuedRefreshToken.rawToken())
                .cookie("XSRF-TOKEN", csrfCookie.getValue())
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .post("/api/v1/auth/refresh");

        assertThat(successResponse.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("유효한 Refresh Token으로 Access Token을 발급하고 Refresh Token을 회전한다")
    void refreshesTokens() throws Exception {
        Cookie csrfCookie = issueCsrfCookie();
        IssuedRefreshToken issuedRefreshToken = issueRefreshToken();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshTokenCookie(issuedRefreshToken), csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(1_800))
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        containsString("refresh_token=")
                ));
    }

    @Test
    @DisplayName("Refresh Token Cookie가 없으면 401과 삭제 Cookie를 반환한다")
    void rejectsMissingRefreshToken() throws Exception {
        Cookie csrfCookie = issueCsrfCookie();

        MockMvcResponse response = RestAssuredMockMvc.given()
                .cookie("XSRF-TOKEN", csrfCookie.getValue())
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .post("/api/v1/auth/refresh");

        assertProblemDetails(
                response,
                401,
                "invalid-refresh-token",
                "Invalid refresh token",
                "Refresh Token이 유효하지 않습니다.",
                "INVALID_REFRESH_TOKEN",
                "/api/v1/auth/refresh"
        );
        assertThat(response.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.headers().getValues(HttpHeaders.SET_COOKIE))
                .anySatisfy(cookie -> assertThat(cookie).contains("refresh_token=;"));
    }

    @Test
    @DisplayName("Refresh Token을 두 번 사용하면 두 번째 요청을 거부한다")
    void rejectsReusedRefreshToken() throws Exception {
        Cookie csrfCookie = issueCsrfCookie();
        IssuedRefreshToken issuedRefreshToken = issueRefreshToken();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshTokenCookie(issuedRefreshToken), csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(status().isOk());

        Cookie nextCsrfCookie = issueCsrfCookie();
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshTokenCookie(issuedRefreshToken), nextCsrfCookie)
                        .header("X-XSRF-TOKEN", nextCsrfCookie.getValue()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("로그아웃하면 Refresh Token을 폐기하고 Cookie를 삭제한다")
    void logsOut() throws Exception {
        Cookie csrfCookie = issueCsrfCookie();
        IssuedRefreshToken issuedRefreshToken = issueRefreshToken();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(refreshTokenCookie(issuedRefreshToken), csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        containsString("refresh_token=;")
                ));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshTokenCookie(issuedRefreshToken), csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("로그아웃 요청에 CSRF Header가 없으면 Refresh Token을 폐기하지 않는다")
    void rejectsLogoutWithoutCsrfHeader() throws Exception {
        Cookie csrfCookie = issueCsrfCookie();
        IssuedRefreshToken issuedRefreshToken = issueRefreshToken();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(refreshTokenCookie(issuedRefreshToken), csrfCookie))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(refreshTokenCookie(issuedRefreshToken), csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Refresh Token Cookie가 없어도 로그아웃할 수 있다")
    void logsOutWithoutRefreshToken() throws Exception {
        Cookie csrfCookie = issueCsrfCookie();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(status().isNoContent())
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        containsString("refresh_token=;")
                ));
    }

    @Test
    @DisplayName("허용된 Frontend Origin의 CORS 사전 요청을 허용한다")
    void allowsFrontendCorsPreflight() throws Exception {
        mockMvc.perform(options("/api/v1/auth/refresh")
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-XSRF-TOKEN"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        FRONTEND_ORIGIN
                ))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,
                        "true"
                ));
    }

    private Cookie issueCsrfCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();

        return findCurrentCsrfCookie(result);
    }

    private Cookie findCurrentCsrfCookie(MvcResult result) {
        return Arrays.stream(result.getResponse().getCookies())
                .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()))
                .filter(cookie -> "/api/v1".equals(cookie.getPath()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("신규 경로의 CSRF Cookie가 없습니다."));
    }

    private Cookie csrfCookie(String value, String path) {
        Cookie cookie = new Cookie("XSRF-TOKEN", value);
        cookie.setPath(path);
        return cookie;
    }

    private IssuedRefreshToken issueRefreshToken() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        User user = userRepository.save(
                new User(
                        "refresh-" + UUID.randomUUID() + "@example.com",
                        "하루들",
                        now
                )
        );

        return refreshTokenService.issue(user, now);
    }

    private Cookie refreshTokenCookie(IssuedRefreshToken issuedRefreshToken) {
        return new Cookie("refresh_token", issuedRefreshToken.rawToken());
    }

    private void assertProblemDetails(
            MockMvcResponse response,
            int status,
            String typeSlug,
            String title,
            String detail,
            String code,
            String instance
    ) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.contentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.jsonPath().getString("type"))
                .isEqualTo("urn:harudle:problem:" + typeSlug);
        assertThat(response.jsonPath().getString("title")).isEqualTo(title);
        assertThat(response.jsonPath().getInt("status")).isEqualTo(status);
        assertThat(response.jsonPath().getString("detail")).isEqualTo(detail);
        assertThat(response.jsonPath().getString("instance")).isEqualTo(instance);
        assertThat(response.jsonPath().getString("code")).isEqualTo(code);
        assertThat(response.jsonPath().getString("traceId")).isNotBlank();
    }
}
