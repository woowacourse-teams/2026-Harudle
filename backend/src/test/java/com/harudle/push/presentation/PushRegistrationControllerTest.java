package com.harudle.push.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.auth.application.AccessTokenService;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class PushRegistrationControllerTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Autowired private MockMvc mockMvc;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private JdbcTemplate jdbc;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID aliceSession = UUID.randomUUID();
    private final UUID bobSession = UUID.randomUUID();
    private final UUID device = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.mockMvc(mockMvc);
        user(alice);
        user(bob);
        session(aliceSession, alice);
        session(bobSession, bob);
    }

    @AfterEach
    void tearDown() {
        RestAssuredMockMvc.reset();
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", alice, bob);
    }

    @Test
    @DisplayName("같은 세션의 동일한 등록 요청은 ID와 버전을 그대로 반환한다")
    void repeatedPutReturnsSameRegistration() {
        MockMvcResponse first = put(alice, aliceSession, device, Map.of("fid", "F1"));
        MockMvcResponse repeated = put(alice, aliceSession, device, Map.of("fid", "F1"));

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(repeated.jsonPath().getString("id")).isEqualTo(first.jsonPath().getString("id"));
        assertThat(repeated.jsonPath().getInt("version")).isZero();
        assertThat(count("SELECT count(*) FROM push_registrations WHERE device_id = ?", device)).isEqualTo(1);
    }

    @Test
    @DisplayName("FID 변경은 현재 ID와 버전이 있을 때만 저장하고 오래된 요청에는 현재 값을 알려준다")
    void stalePutReturnsCurrentRegistration() {
        MockMvcResponse first = put(alice, aliceSession, device, Map.of("fid", "F1"));
        String id = first.jsonPath().getString("id");
        MockMvcResponse changed = put(alice, aliceSession, device,
                Map.of("fid", "F2", "registrationId", id, "version", 0));
        MockMvcResponse stale = put(alice, aliceSession, device,
                Map.of("fid", "F1", "registrationId", id, "version", 0));

        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(changed.jsonPath().getInt("version")).isEqualTo(1);
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.jsonPath().getString("code")).isEqualTo("STALE_PUSH_REGISTRATION");
        assertThat(stale.jsonPath().getString("current.id")).isEqualTo(id);
        assertThat(stale.jsonPath().getInt("current.version")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT recipient_value FROM push_registrations WHERE id = ?",
                String.class, UUID.fromString(id))).isEqualTo("F2");
    }

    @Test
    @DisplayName("다른 유효 세션의 기기 등록은 409로 보호한다")
    void rejectsOtherSessionsDevice() {
        put(alice, aliceSession, device, Map.of("fid", "F1"));

        MockMvcResponse conflict = put(bob, bobSession, device, Map.of("fid", "F2"));

        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.jsonPath().getString("code")).isEqualTo("PUSH_REGISTRATION_CONFLICT");
        assertThat(count("SELECT count(*) FROM push_registrations WHERE device_id = ? AND revoked_at IS NULL",
                device)).isEqualTo(1);
    }

    @Test
    @DisplayName("현재 세션이 없는 토큰은 등록을 거절하고 DB에 아무것도 저장하지 않는다")
    void rejectsMissingSession() {
        MockMvcResponse response = put(alice, UUID.randomUUID(), device, Map.of("fid", "F1"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_PUSH_SESSION");
        assertThat(count("SELECT count(*) FROM push_registrations WHERE device_id = ?", device)).isZero();
    }

    @Test
    @DisplayName("다른 사용자의 세션 ID를 담은 토큰으로는 등록할 수 없다")
    void rejectsAnotherUsersSession() {
        MockMvcResponse response = put(alice, bobSession, device, Map.of("fid", "F1"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_PUSH_SESSION");
        assertThat(count("SELECT count(*) FROM push_registrations WHERE device_id = ?", device)).isZero();
    }

    @Test
    @DisplayName("로그인 토큰이 없으면 기기를 등록하거나 해제할 수 없다")
    void rejectsUnauthenticatedRequests() {
        MockMvcResponse registration = RestAssuredMockMvc.given()
                .contentType(MediaType.APPLICATION_JSON_VALUE).body(Map.of("fid", "F1"))
                .put("/api/v1/me/push-registrations/{deviceId}", device);
        MockMvcResponse revocation = RestAssuredMockMvc.given()
                .delete("/api/v1/me/push-registrations/{deviceId}", device);

        assertThat(registration.statusCode()).isEqualTo(401);
        assertThat(revocation.statusCode()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM push_registrations WHERE device_id = ?", device)).isZero();
    }

    @Test
    @DisplayName("등록 ID와 버전이 함께 오지 않으면 400으로 거절한다")
    void rejectsIncompleteVersion() {
        MockMvcResponse response = put(alice, aliceSession, device,
                Map.of("fid", "F1", "version", 0));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
        assertThat(count("SELECT count(*) FROM push_registrations WHERE device_id = ?", device)).isZero();
    }

    @Test
    @DisplayName("본인 등록만 해제하고 같은 요청을 다시 보내도 204를 반환한다")
    void revokeIsIdempotent() {
        put(alice, aliceSession, device, Map.of("fid", "F1"));

        MockMvcResponse other = delete(bob, bobSession, device);
        MockMvcResponse first = delete(alice, aliceSession, device);
        MockMvcResponse repeated = delete(alice, aliceSession, device);

        assertThat(other.statusCode()).isEqualTo(204);
        assertThat(first.statusCode()).isEqualTo(204);
        assertThat(repeated.statusCode()).isEqualTo(204);
        assertThat(count("SELECT count(*) FROM push_registrations WHERE device_id = ? AND revoked_at IS NULL",
                device)).isZero();
    }

    private MockMvcResponse put(UUID user, UUID session, UUID deviceId, Map<String, ?> body) {
        return RestAssuredMockMvc.given().header(HttpHeaders.AUTHORIZATION, bearer(user, session))
                .contentType(MediaType.APPLICATION_JSON_VALUE).body(body)
                .put("/api/v1/me/push-registrations/{deviceId}", deviceId);
    }

    private MockMvcResponse delete(UUID user, UUID session, UUID deviceId) {
        return RestAssuredMockMvc.given().header(HttpHeaders.AUTHORIZATION, bearer(user, session))
                .delete("/api/v1/me/push-registrations/{deviceId}", deviceId);
    }

    private String bearer(UUID user, UUID session) {
        return "Bearer " + accessTokens.issue(user, session, Instant.now()).accessToken();
    }

    private void user(UUID id) {
        jdbc.update("INSERT INTO users (id, name) VALUES (?, '테스트')", id);
    }

    private void session(UUID id, UUID user) {
        jdbc.update("INSERT INTO auth_sessions (id, user_id) VALUES (?, ?)", id, user);
        jdbc.update("""
                INSERT INTO refresh_tokens (user_id, token_hash, expires_at, session_id)
                VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '1 day', ?)
                """, user, UUID.randomUUID().toString().replace("-", "").repeat(2), id);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
