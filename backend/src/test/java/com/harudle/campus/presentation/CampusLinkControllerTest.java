package com.harudle.campus.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.auth.application.AccessTokenService;
import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.support.MockMvcIntegrationTest;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@MockMvcIntegrationTest
@TestPropertySource(properties = {
        "harudle.campus.enabled=true",
        "harudle.campus.redirect-uri=https://campus.harudle.com/auth/harudle/callback",
        "harudle.campus.signing-key-base64=Y2FtcHVzLXNpZ25pbmcta2V5LWZvci10ZXN0LTAxMjM0NTY3ODk="
})
class CampusLinkControllerTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-09T10:00:00Z");
    private static final String KEY = "Y2FtcHVzLXNpZ25pbmcta2V5LWZvci10ZXN0LTAxMjM0NTY3ODk=";
    private static final String STATE = "campus-state-0123456789abcdefghijklmnopqrstuv";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OAuthAccountRepository oauthAccountRepository;

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.mockMvc(mockMvc);
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAll();
        RestAssuredMockMvc.reset();
    }

    @Test
    @DisplayName("로그인한 회원에게 캠퍼스 콜백 주소와 서명 토큰을 발급한다")
    void issuesToken() throws Exception {
        User user = saveUser("눈누난나");

        MockMvcResponse response = issue(bearerToken(user.getId()), STATE);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.jsonPath().getString("redirectUri"))
                .isEqualTo("https://campus.harudle.com/auth/harudle/callback");
        SignedJWT jwt = SignedJWT.parse(response.jsonPath().getString("token"));
        assertThat(jwt.verify(new MACVerifier(Base64.getDecoder().decode(KEY)))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getJWTClaimsSet().getStringClaim("nickname")).isEqualTo("눈누난나");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("state")).isEqualTo(STATE);
    }

    @Test
    @DisplayName("로그인하지 않았거나 state 형식이 틀리면 발급하지 않는다")
    void rejectsWithoutLoginOrValidState() {
        User user = saveUser("눈누난나");

        assertThat(issue(null, STATE).statusCode()).isEqualTo(401);
        assertThat(issue(bearerToken(user.getId()), "short").statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("닉네임이 없는 회원은 공개 프로필 배정을 먼저 요구한다")
    void rejectsWithoutPublicProfile() {
        User user = userRepository.save(new User("legacy@example.com", "기존이름", CREATED_AT));
        saveOAuthAccount(user);

        MockMvcResponse response = issue(bearerToken(user.getId()), STATE);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.jsonPath().getString("code")).isEqualTo("PUBLIC_PROFILE_NOT_ASSIGNED");
    }

    private MockMvcResponse issue(String authorization, String state) {
        var request = RestAssuredMockMvc.given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(Map.of("state", state));
        if (authorization != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.post("/api/v1/campus/link-tokens");
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
}
