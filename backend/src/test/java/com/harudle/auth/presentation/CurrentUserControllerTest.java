package com.harudle.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.auth.application.AccessTokenService;
import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.UserRepository;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OAuthAccountRepository oauthAccountRepository;

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.mockMvc(mockMvc);
    }

    @AfterEach
    void tearDown() {
        RestAssuredMockMvc.reset();
    }

    @Test
    @DisplayName("유효한 Access Token으로 내 프로필을 조회한다")
    void findsCurrentUser() {
        User user = saveUser("user@example.com", "하루들");
        saveOAuthAccount(user, "12345", "user@example.com");

        MockMvcResponse response = RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user.getId()))
                .get("/api/v1/me");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.contentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.jsonPath().getString("id")).isEqualTo(user.getId().toString());
        assertThat(response.jsonPath().getString("name")).isEqualTo("하루들");
        assertThat(response.jsonPath().getString("email")).isEqualTo("user@example.com");
        assertThat(response.jsonPath().getString("role")).isEqualTo("USER");
        assertThat(response.jsonPath().getList("oauthProviders")).containsExactly("kakao");
        assertThat(response.jsonPath().getString("oauthProvider")).isNull();
        assertThat(response.jsonPath().getString("createdAt")).isEqualTo(CREATED_AT.toString());
    }

    @Test
    @DisplayName("관리자 사용자의 내 프로필 조회 응답에 ADMIN role을 포함한다")
    void findsCurrentAdmin() {
        User user = saveUser("admin@example.com", "관리자");
        grantAdminRole(user);
        saveOAuthAccount(user, "67890", "admin@example.com");

        MockMvcResponse response = RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user.getId()))
                .get("/api/v1/me");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("role")).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("Access Token 없이 내 프로필을 조회할 수 없다")
    void rejectsUnauthenticatedRequest() {
        MockMvcResponse response = RestAssuredMockMvc.given()
                .get("/api/v1/me");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("존재하지 않는 사용자 식별자의 Access Token으로 내 프로필을 조회할 수 없다")
    void rejectsMissingCurrentUser() {
        MockMvcResponse response = RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, bearerToken(UUID.randomUUID()))
                .get("/api/v1/me");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.contentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.jsonPath().getString("type"))
                .isEqualTo("urn:harudle:problem:invalid-current-user");
        assertThat(response.jsonPath().getString("title")).isEqualTo("Invalid current user");
        assertThat(response.jsonPath().getInt("status")).isEqualTo(401);
        assertThat(response.jsonPath().getString("detail"))
                .isEqualTo("현재 로그인 사용자를 확인할 수 없습니다.");
        assertThat(response.jsonPath().getString("instance")).isEqualTo("/api/v1/me");
        assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_CURRENT_USER");
        assertThat(response.jsonPath().getString("traceId")).isNotBlank();
    }

    @Test
    @DisplayName("OAuth 계정이 없는 사용자 식별자의 Access Token으로 내 프로필을 조회할 수 없다")
    void rejectsUserWithoutOAuthAccount() {
        User user = saveUser("user-without-account@example.com", "하루들");

        MockMvcResponse response = RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user.getId()))
                .get("/api/v1/me");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_CURRENT_USER");
        assertThat(response.jsonPath().getString("traceId")).isNotBlank();
    }

    private User saveUser(String email, String name) {
        return userRepository.save(new User(email, name, CREATED_AT));
    }

    private void grantAdminRole(User user) {
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user.getId());
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
