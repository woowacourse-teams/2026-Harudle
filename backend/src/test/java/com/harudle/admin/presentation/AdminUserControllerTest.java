package com.harudle.admin.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.auth.application.AccessTokenService;
import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
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
class AdminUserControllerTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final Instant CREATED_AT = Instant.parse("2026-08-25T01:00:00Z");

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("관리자는 이름 일부로 사용자를 검색하고 오늘 남은 생성 횟수를 조회한다")
    void searchesUserByPartialName() throws Exception {
        User admin = userRepository.save(new User("admin@example.com", "관리자", CREATED_AT));
        grantAdminRole(admin);
        User user = userRepository.save(new User("harudle@example.com", "하루들이", CREATED_AT));
        oauthAccountRepository.save(new OAuthAccount(
                user,
                OAuthProvider.KAKAO,
                "admin-search-user",
                "harudle@example.com",
                CREATED_AT
        ));
        saveTodayUsage(user, 2, 3);

        mockMvc.perform(get("/api/v1/admin/users")
                        .queryParam("query", "루들")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(user.getId().toString()))
                .andExpect(jsonPath("$.content[0].name").value("하루들이"))
                .andExpect(jsonPath("$.content[0].email").value("harudle@example.com"))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.content[0].lastLoginAt").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.content[0].remainingGenerationCount").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("일반 사용자는 관리자 사용자 검색 API를 호출할 수 없다")
    void rejectsRegularUser() throws Exception {
        User user = userRepository.save(new User("regular@example.com", "일반 사용자", CREATED_AT));

        mockMvc.perform(get("/api/v1/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("관리자는 사용자의 사용량과 최근 생성 5건을 조회한다")
    void findsUserDetail() throws Exception {
        User admin = userRepository.save(new User("detail-admin@example.com", "관리자", CREATED_AT));
        grantAdminRole(admin);
        User user = userRepository.save(new User("detail@example.com", "상세 사용자", CREATED_AT));
        saveTodayUsage(user, 2, 5);
        for (int index = 0; index < 6; index++) {
            saveGeneration(user, CREATED_AT.plusSeconds(index), index == 0);
        }

        mockMvc.perform(get("/api/v1/admin/users/{userId}", user.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.usageDate").value(LocalDate.now(ZoneId.of("Asia/Seoul")).toString()))
                .andExpect(jsonPath("$.usedGenerationCount").value(2))
                .andExpect(jsonPath("$.dailyGenerationLimit").value(5))
                .andExpect(jsonPath("$.remainingGenerationCount").value(3))
                .andExpect(jsonPath("$.recentGenerations.length()").value(5))
                .andExpect(jsonPath("$.recentGenerations[0].status").value("FAILED"))
                .andExpect(jsonPath("$.recentGenerations[0].failureCode").value("AI_PROVIDER_ERROR"));
    }

    @Test
    @DisplayName("탈퇴한 사용자도 관리자는 상세 조회할 수 있다")
    void findsDeletedUserDetail() throws Exception {
        User admin = userRepository.save(new User("deleted-admin@example.com", "관리자", CREATED_AT));
        grantAdminRole(admin);
        User user = userRepository.save(new User("deleted@example.com", "탈퇴 사용자", CREATED_AT));
        jdbcTemplate.update("UPDATE users SET deleted_at = ? WHERE id = ?", CREATED_AT.plusSeconds(1), user.getId());

        mockMvc.perform(get("/api/v1/admin/users/{userId}", user.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"))
                .andExpect(jsonPath("$.recentGenerations.length()").value(0));
    }

    @Test
    @DisplayName("존재하지 않는 사용자 상세 조회는 404를 반환한다")
    void rejectsMissingUserDetail() throws Exception {
        User admin = userRepository.save(new User("missing-admin@example.com", "관리자", CREATED_AT));
        grantAdminRole(admin);

        mockMvc.perform(get("/api/v1/admin/users/{userId}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("일반 사용자는 사용자 상세 조회 API를 호출할 수 없다")
    void rejectsRegularUserDetail() throws Exception {
        User user = userRepository.save(new User("regular-detail@example.com", "일반 사용자", CREATED_AT));

        mockMvc.perform(get("/api/v1/admin/users/{userId}", user.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("관리자가 변경한 일일 생성 한도는 오늘과 이후 사용량에 적용된다")
    void changesDailyGenerationLimit() throws Exception {
        User admin = userRepository.save(new User("limit-admin@example.com", "관리자", CREATED_AT));
        grantAdminRole(admin);
        User user = userRepository.save(new User("limit-user@example.com", "한도 사용자", CREATED_AT));
        saveTodayUsage(user, 1, 3);

        mockMvc.perform(put("/api/v1/admin/users/{userId}/generation-limit", user.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limitCount\":5}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/users/{userId}", user.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedGenerationCount").value(1))
                .andExpect(jsonPath("$.dailyGenerationLimit").value(5))
                .andExpect(jsonPath("$.remainingGenerationCount").value(4));
    }

    private void grantAdminRole(User admin) {
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", admin.getId());
    }

    private void saveTodayUsage(User user, int usedCount, int limitCount) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        jdbcTemplate.update("""
                INSERT INTO daily_generation_usage(user_id, usage_date, used_count, limit_count)
                VALUES (?, ?, ?, ?)
                """, user.getId(), today, usedCount, limitCount);
    }

    private void saveGeneration(User user, Instant createdAt, boolean failed) {
        UUID diaryId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO diaries(id, user_id, diary_date, source_text, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, diaryId, user.getId(), createdAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),
                "관리자 테스트 일기", createdAt, createdAt);
        UUID generationId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO diary_generations(
                    id, diary_id, prompt_id, idempotency_key, request_fingerprint, status,
                    error_code, created_at, updated_at, completed_at)
                VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?)
                """, generationId, diaryId, UUID.randomUUID(),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                failed ? "FAILED" : "SUCCEEDED", failed ? "AI_PROVIDER_ERROR" : null,
                createdAt, createdAt, failed ? createdAt.plusSeconds(1) : createdAt.plusSeconds(1));
    }

    private String bearerToken(User user) {
        return "Bearer " + accessTokenService.issue(user.getId(), Instant.now()).accessToken();
    }
}
