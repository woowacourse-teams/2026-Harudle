package com.harudle.admin.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.auth.application.AccessTokenService;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.support.MockMvcIntegrationTest;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@MockMvcIntegrationTest
class AdminStatsControllerTest {

    private static final String PATH = "/api/v1/admin/stats";
    private static final Instant PAST = Instant.parse("2026-08-25T01:00:00Z");
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.mockMvc(mockMvc);
    }

    @AfterEach
    void tearDown() {
        createdUserIds.forEach(id -> {
            jdbcTemplate.update("DELETE FROM guest_sessions WHERE guest_user_id = ?", id);
            jdbcTemplate.update("DELETE FROM moderation_states WHERE target_type = 'USER' AND target_id = ?", id);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", id);
        });
        RestAssuredMockMvc.reset();
    }

    @Test
    @DisplayName("회원 수는 탈퇴와 게스트를 빼고 정지 회원을 포함하며 오늘 가입은 한국 시간 하루로 센다")
    void countsMembersAndTodaySignupsInServiceZone() {
        User admin = saveUser("관리자", PAST);
        grantAdminRole(admin);
        MockMvcResponse before = adminRequest(admin).get(PATH);
        assertThat(before.statusCode()).isEqualTo(200);

        Instant todayStart = LocalDate.now(SERVICE_ZONE).atStartOfDay(SERVICE_ZONE).toInstant();
        saveUser("오늘 첫 가입", todayStart);
        saveUser("오늘 가입", Instant.now());
        saveUser("어제 마지막 가입", todayStart.minusSeconds(1));
        User suspended = saveUser("정지 회원", Instant.now());
        restrict(suspended);
        User withdrawn = saveUser("탈퇴 회원", Instant.now());
        markDeleted(withdrawn);
        User guest = saveUser("게스트", Instant.now());
        saveGuestSession(guest);

        MockMvcResponse after = adminRequest(admin).get(PATH);

        assertThat(after.statusCode()).isEqualTo(200);
        assertThat(after.jsonPath().getLong("memberCount") - before.jsonPath().getLong("memberCount"))
                .isEqualTo(4);
        assertThat(after.jsonPath().getLong("todaySignupCount")
                - before.jsonPath().getLong("todaySignupCount"))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("관리자가 아니면 통계를 조회할 수 없다")
    void rejectsNonAdministrator() {
        User member = saveUser("일반 회원", PAST);

        assertThat(RestAssuredMockMvc.given().get(PATH).statusCode()).isEqualTo(401);
        assertThat(adminRequest(member).get(PATH).statusCode()).isEqualTo(403);
    }

    private io.restassured.module.mockmvc.specification.MockMvcRequestSpecification adminRequest(User user) {
        return RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, "Bearer "
                        + accessTokenService.issue(user.getId(), UUID.randomUUID(), Instant.now()).accessToken());
    }

    private User saveUser(String name, Instant createdAt) {
        User user = userRepository.save(new User(null, name, createdAt));
        createdUserIds.add(user.getId());
        return user;
    }

    private void grantAdminRole(User user) {
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user.getId());
    }

    private void markDeleted(User user) {
        jdbcTemplate.update("UPDATE users SET deleted_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), user.getId());
    }

    private void restrict(User user) {
        jdbcTemplate.update("""
                INSERT INTO moderation_states(target_type, target_id, restricted, version, reason, changed_at)
                VALUES ('USER', ?, TRUE, 1, '테스트 정지', ?)
                """, user.getId(), Timestamp.from(Instant.now()));
    }

    private void saveGuestSession(User user) {
        jdbcTemplate.update("""
                INSERT INTO guest_sessions(id, guest_user_id, token_hash, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), user.getId(), "b".repeat(64),
                Timestamp.from(Instant.now().plusSeconds(3600)),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }
}
