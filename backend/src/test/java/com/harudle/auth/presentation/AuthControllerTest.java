package com.harudle.auth.presentation;

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
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
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

    @Test
    @DisplayName("CSRF Token을 발급하고 Cookie와 응답 본문으로 전달한다")
    void issuesCsrfToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();

        Cookie csrfCookie = result.getResponse().getCookie("XSRF-TOKEN");

        org.assertj.core.api.Assertions.assertThat(csrfCookie).isNotNull();
        org.assertj.core.api.Assertions.assertThat(csrfCookie.isHttpOnly()).isFalse();
        org.assertj.core.api.Assertions.assertThat(csrfCookie.getPath()).isEqualTo("/api/v1");
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .contains(csrfCookie.getValue());
    }

    @Test
    @DisplayName("Refresh 요청에 CSRF Header가 없으면 토큰을 회전하지 않는다")
    void rejectsRefreshWithoutCsrfHeader() throws Exception {
        Cookie csrfCookie = issueCsrfCookie();
        IssuedRefreshToken issuedRefreshToken = issueRefreshToken();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshTokenCookie(issuedRefreshToken), csrfCookie))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshTokenCookie(issuedRefreshToken), csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(status().isOk());
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

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:harudle:problem:invalid-refresh-token"))
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        containsString("refresh_token=;")
                ));
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
                .andExpect(status().isForbidden());

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

        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        org.assertj.core.api.Assertions.assertThat(cookie).isNotNull();
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
}
