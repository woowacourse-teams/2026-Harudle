package com.harudle.campus.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.auth.application.AccessTokenService;
import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.support.MockMvcIntegrationTest;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@MockMvcIntegrationTest
@TestPropertySource(properties = {
        "harudle.campus.enabled=true",
        "harudle.campus.redirect-uri=https://campus.harudle.com/auth/harudle/callback",
        "harudle.campus.client-secret-sha256=ecbd5a88c5faf812fc5c7efd899c8aae4303fcb9011ff85e016734f0fcd64771"
})
class CampusLinkControllerTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-09T10:00:00Z");
    private static final String SECRET = "campus-client-secret-for-test";
    private static final String STATE = "campus-state-0123456789abcdefghijklmnopqrstuv";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OAuthAccountRepository oauthAccountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.mockMvc(mockMvc);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM campus_link_codes");
        userRepository.deleteAll();
        RestAssuredMockMvc.reset();
    }

    @Test
    @DisplayName("로그인한 회원이 받은 연결 코드를 캠퍼스 서버가 한 번만 교환한다")
    void issuesAndExchangesCodeOnce() {
        User user = saveUser("눈누난나");

        MockMvcResponse issued = issue(bearerToken(user.getId()), STATE);

        assertThat(issued.statusCode()).isEqualTo(201);
        assertThat(issued.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(issued.jsonPath().getString("redirectUri"))
                .isEqualTo("https://campus.harudle.com/auth/harudle/callback");
        String code = issued.jsonPath().getString("code");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM campus_link_codes WHERE code_hash = ?",
                Integer.class, code)).isZero();

        MockMvcResponse exchanged = exchange(basic(SECRET), code, STATE);

        assertThat(exchanged.statusCode()).isEqualTo(200);
        assertThat(exchanged.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(exchanged.jsonPath().getString("userId")).isEqualTo(user.getId().toString());
        assertThat(exchanged.jsonPath().getString("nickname")).isEqualTo("눈누난나");
        assertThat(exchanged.jsonPath().getMap("")).doesNotContainKeys("email", "name");

        MockMvcResponse again = exchange(basic(SECRET), code, STATE);
        assertThat(again.statusCode()).isEqualTo(400);
        assertThat(again.jsonPath().getString("code")).isEqualTo("INVALID_CAMPUS_LINK_CODE");
    }

    @Test
    @DisplayName("로그인하지 않았거나 state 형식이 틀리면 발급하지 않는다")
    void rejectsIssueWithoutLoginOrValidState() {
        User user = saveUser("눈누난나");

        assertThat(issue(null, STATE).statusCode()).isEqualTo(401);
        assertThat(issue(bearerToken(user.getId()), "short").statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("닉네임이 없는 회원은 공개 프로필 배정을 먼저 요구한다")
    void rejectsIssueWithoutPublicProfile() {
        User user = userRepository.save(new User("legacy@example.com", "기존이름", CREATED_AT));
        saveOAuthAccount(user);

        MockMvcResponse response = issue(bearerToken(user.getId()), STATE);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.jsonPath().getString("code")).isEqualTo("PUBLIC_PROFILE_NOT_ASSIGNED");
    }

    @Test
    @DisplayName("비밀값이 틀리거나 state가 다르면 교환하지 않고, 코드는 그대로 남는다")
    void rejectsWrongClientOrState() {
        User user = saveUser("눈누난나");
        String code = issue(bearerToken(user.getId()), STATE).jsonPath().getString("code");

        MockMvcResponse wrongSecret = exchange(basic("wrong-secret"), code, STATE);
        assertThat(wrongSecret.statusCode()).isEqualTo(401);
        assertThat(wrongSecret.jsonPath().getString("code")).isEqualTo("INVALID_CAMPUS_CLIENT");
        assertThat(exchange(null, code, STATE).statusCode()).isEqualTo(401);
        assertThat(exchange("Bearer " + SECRET, code, STATE).statusCode()).isEqualTo(401);

        assertThat(exchange(basic(SECRET), code, "other-state-0123456789abcdefghijklmnopqrstuvw").statusCode())
                .isEqualTo(400);
    }

    private MockMvcResponse issue(String authorization, String state) {
        var request = RestAssuredMockMvc.given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(Map.of("state", state));
        if (authorization != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.post("/api/v1/campus/link-codes");
    }

    private MockMvcResponse exchange(String authorization, String code, String state) {
        var request = RestAssuredMockMvc.given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(Map.of("code", code, "state", state));
        if (authorization != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.post("/api/v1/campus/link-codes/exchange");
    }

    private User saveUser(String nickname) {
        User user = new User(UUID.randomUUID() + "@example.com", "하루들", CREATED_AT);
        user.assignInitialPublicProfile(nickname, 5, CREATED_AT);
        user = userRepository.save(user);
        saveOAuthAccount(user);
        return user;
    }

    private void saveOAuthAccount(User user) {
        oauthAccountRepository.save(new OAuthAccount(
                user,
                OAuthProvider.KAKAO,
                UUID.randomUUID().toString(),
                user.getPrimaryEmail(),
                CREATED_AT
        ));
    }

    private String bearerToken(UUID userId) {
        return "Bearer " + accessTokenService.issue(userId, UUID.randomUUID(), Instant.now()).accessToken();
    }

    private String basic(String secret) {
        return "Basic " + Base64.getEncoder().encodeToString(("campus:" + secret).getBytes(StandardCharsets.UTF_8));
    }
}
