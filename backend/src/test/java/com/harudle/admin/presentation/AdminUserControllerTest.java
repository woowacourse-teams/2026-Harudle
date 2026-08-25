package com.harudle.admin.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
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

    private String bearerToken(User user) {
        return "Bearer " + accessTokenService.issue(user.getId(), Instant.now()).accessToken();
    }
}
