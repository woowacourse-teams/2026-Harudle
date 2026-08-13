package com.harudle.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.auth.application.AccessTokenService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@Import(SecurityTestController.class)
class SecurityConfigTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccessTokenService accessTokenService;

    @Test
    @DisplayName("Access Token 없이 보호 API에 접근할 수 없다")
    void rejectsUnauthenticatedApiRequest() throws Exception {
        mockMvc.perform(get("/api/v1/test-auth"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("JSESSIONID만으로 보호 API에 접근할 수 없다")
    void rejectsSessionOnlyApiRequest() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("oauth2-authorization-request", "temporary-state");

        mockMvc.perform(get("/api/v1/test-auth").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("유효한 Access Token으로 보호 API에 접근할 수 있다")
    void acceptsValidAccessToken() throws Exception {
        String accessToken = issueAccessToken();

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
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("공개 API는 Access Token 없이 접근할 수 있다")
    void allowsPublicApi() throws Exception {
        mockMvc.perform(get("/api/v1/public/test"))
                .andExpect(status().isOk())
                .andExpect(content().string("public"));
    }

    @Test
    @DisplayName("등록하지 않은 경로는 접근할 수 없다")
    void rejectsUnregisteredPath() throws Exception {
        mockMvc.perform(get("/unregistered")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueAccessToken()))
                .andExpect(status().isForbidden());
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

    private String issueAccessToken() {
        return accessTokenService.issue(
                UUID.randomUUID(),
                Instant.now()
        ).accessToken();
    }
}
