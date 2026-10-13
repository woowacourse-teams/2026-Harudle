package com.harudle.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.harudle.auth.application.AccessTokenService;
import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.UserRepository;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import java.time.Instant;
import java.util.Map;
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
@SpringBootTest(properties = {
        "harudle.profile.image-base-url=https://www.harudle.com/profile-images/v1"
})
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
        User user = new User("user@example.com", "하루들", CREATED_AT);
        user.assignInitialPublicProfile("행복한 고양이", 5, CREATED_AT);
        user = userRepository.save(user);
        saveOAuthAccount(user, "12345", "user@example.com");

        MockMvcResponse response = RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user.getId()))
                .get("/api/v1/me");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.contentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.jsonPath().getString("id")).isEqualTo(user.getId().toString());
        assertThat(response.jsonPath().getString("name")).isEqualTo("하루들");
        assertThat(response.jsonPath().getString("nickname")).isEqualTo("행복한 고양이");
        assertThat(response.jsonPath().getString("profileImageUrl"))
                .isEqualTo("https://www.harudle.com/profile-images/v1/5.png");
        assertThat(response.jsonPath().getString("email")).isEqualTo("user@example.com");
        assertThat(response.jsonPath().getString("role")).isEqualTo("USER");
        assertThat(response.jsonPath().getList("oauthProviders")).containsExactly("kakao");
        assertThat(response.jsonPath().getString("oauthProvider")).isNull();
        assertThat(response.jsonPath().getString("createdAt")).isEqualTo(CREATED_AT.toString());
    }

    @Test
    @DisplayName("백필 전 회원은 공개 프로필을 새로 배정하지 않고 null로 반환한다")
    void findsCurrentUserWithoutPublicProfile() {
        User user = saveUser("legacy@example.com", "기존OAuth이름");
        saveOAuthAccount(user, "legacy-subject", "legacy@example.com");

        MockMvcResponse response = RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user.getId()))
                .get("/api/v1/me");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("name")).isEqualTo("기존OAuth이름");
        assertThat(response.jsonPath().getMap("")).containsKeys("nickname", "profileImageUrl");
        assertThat(response.jsonPath().getString("nickname")).isNull();
        assertThat(response.jsonPath().getString("profileImageUrl")).isNull();
        User saved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(saved.getNickname()).isNull();
        assertThat(saved.getProfileImageCode()).isNull();
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

    @Test
    @DisplayName("닉네임 변경 후 내 정보 조회에서 새 닉네임과 기존 이미지를 반환한다")
    void changesNickname() {
        User user = saveProfileUser();
        String previousNickname = user.getNickname();

        MockMvcResponse response = changeNickname(user, "초록");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body().asString()).isEmpty();
        assertThat(response.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        MockMvcResponse profile = RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user.getId()))
                .get("/api/v1/me");
        assertThat(profile.jsonPath().getString("nickname")).isEqualTo("초록");
        assertThat(profile.jsonPath().getString("name")).isEqualTo("원래이름");
        assertThat(profile.jsonPath().getString("profileImageUrl"))
                .isEqualTo("https://www.harudle.com/profile-images/v1/3.png");
        assertThat(jdbcTemplate.queryForObject("SELECT assigned_user_id FROM nickname_candidates WHERE nickname = ?",
                UUID.class, previousNickname)).isNull();
    }

    @Test
    @DisplayName("잘못된 닉네임·중복·공개 프로필 미배정은 각각 오류로 안내한다")
    void rejectsInvalidNicknameChanges() {
        User user = saveProfileUser();
        User other = saveProfileUser();

        MockMvcResponse invalid = changeNickname(user, "연속  공백");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
        assertThat(invalid.jsonPath().getString("errors[0].field")).isEqualTo("nickname");

        MockMvcResponse duplicate = changeNickname(user, other.getNickname());
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.jsonPath().getString("code")).isEqualTo("NICKNAME_ALREADY_IN_USE");

        User legacy = saveUser("nickname-legacy@example.com", "기존회원");
        saveOAuthAccount(legacy, legacy.getId().toString(), null);
        MockMvcResponse unassigned = changeNickname(legacy, "새별명");
        assertThat(unassigned.statusCode()).isEqualTo(409);
        assertThat(unassigned.jsonPath().getString("code")).isEqualTo("PUBLIC_PROFILE_NOT_ASSIGNED");
        assertThat(userRepository.findById(user.getId()).orElseThrow().getNickname()).isEqualTo(user.getNickname());
    }

    @Test
    @DisplayName("닉네임 변경은 Bearer 인증이 필요하며 CSRF 토큰을 요구하지 않는다")
    void requiresBearerAuthenticationForNicknameChange() {
        User user = saveProfileUser();
        MockMvcResponse unauthenticated = RestAssuredMockMvc.given()
                .postProcessors(csrf())
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(Map.of("nickname", "새별명"))
                .patch("/api/v1/me/nickname");
        assertThat(unauthenticated.statusCode()).isEqualTo(401);

        MockMvcResponse unauthenticatedWithoutCsrf = RestAssuredMockMvc.given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(Map.of("nickname", "새별명"))
                .patch("/api/v1/me/nickname");
        assertThat(unauthenticatedWithoutCsrf.statusCode()).isEqualTo(401);
        assertThat(unauthenticatedWithoutCsrf.jsonPath().getString("code")).isEqualTo("UNAUTHORIZED");

        assertThat(changeNickname(user, "새별명").statusCode()).isEqualTo(204);
    }

    private MockMvcResponse changeNickname(User user, String nickname) {
        return RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user.getId()))
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(Map.of("nickname", nickname))
                .patch("/api/v1/me/nickname");
    }

    private User saveProfileUser() {
        String nickname = jdbcTemplate.queryForObject("""
                SELECT c.nickname FROM nickname_candidates c
                WHERE c.assigned_user_id IS NULL
                  AND NOT EXISTS (SELECT 1 FROM users u WHERE u.nickname = c.nickname AND u.deleted_at IS NULL)
                ORDER BY c.allocation_order LIMIT 1
                """, String.class);
        User user = new User(null, "원래이름", CREATED_AT);
        user.assignInitialPublicProfile(nickname, 3, CREATED_AT);
        user = userRepository.save(user);
        saveOAuthAccount(user, user.getId().toString(), null);
        jdbcTemplate.update("UPDATE nickname_candidates SET assigned_user_id = ? WHERE nickname = ?",
                user.getId(), nickname);
        return user;
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
