package com.harudle.auth.presentation;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.auth.application.AccessTokenService;
import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class CurrentUserControllerTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final Instant CREATED_AT = Instant.parse("2026-08-11T10:00:00Z");

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
    private OAuthAccountRepository oauthAccountRepository;

    @Test
    @DisplayName("유효한 Access Token으로 내 프로필을 조회한다")
    void findsCurrentUser() throws Exception {
        User user = saveUser("user@example.com", "하루들");
        saveOAuthAccount(user, "12345", "user@example.com");

        mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user.getId())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.name").value("하루들"))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.oauthProviders").isArray())
                .andExpect(jsonPath("$.oauthProviders[0]").value("kakao"))
                .andExpect(jsonPath("$.oauthProvider").doesNotExist())
                .andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()));
    }

    @Test
    @DisplayName("Access Token 없이 내 프로필을 조회할 수 없다")
    void rejectsUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("존재하지 않는 사용자 식별자의 Access Token으로 내 프로필을 조회할 수 없다")
    void rejectsMissingCurrentUser() throws Exception {
        mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_CURRENT_USER"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(
                        HttpHeaders.CONTENT_TYPE,
                        containsString(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                ));
    }

    @Test
    @DisplayName("OAuth 계정이 없는 사용자 식별자의 Access Token으로 내 프로필을 조회할 수 없다")
    void rejectsUserWithoutOAuthAccount() throws Exception {
        User user = saveUser("user-without-account@example.com", "하루들");

        mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user.getId())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENT_USER"));
    }

    private User saveUser(String email, String name) {
        return userRepository.save(new User(email, name, CREATED_AT));
    }

    private OAuthAccount saveOAuthAccount(User user, String subject, String email) {
        return oauthAccountRepository.save(
                new OAuthAccount(
                        user,
                        OAuthProvider.KAKAO,
                        subject,
                        email,
                        CREATED_AT
                )
        );
    }

    private String bearerToken(UUID userId) {
        return "Bearer " + accessTokenService.issue(userId, Instant.now()).accessToken();
    }
}
