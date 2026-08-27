package com.harudle.admin.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.auth.application.AccessTokenService;
import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.generation.domain.GenerationPrompt;
import com.harudle.generation.repository.GenerationPromptRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

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
    private GenerationPromptRepository generationPromptRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("이름으로 검색하고 탈퇴 사용자와 오늘 사용량을 함께 반환하며 게스트는 제외한다")
    void searchesUsersWithUsageAndExcludesGuests() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User activeUser = saveUser("하루들이", CREATED_AT.plusSeconds(2));
        User deletedUser = saveUser("하루들이");
        User guestUser = saveUser("하루들이");
        markDeleted(deletedUser);
        saveOAuthAccount(activeUser);
        saveTodayUsage(activeUser, 2, 3);
        saveGuestSession(guestUser);

        mockMvc.perform(get("/api/v1/admin/users")
                        .queryParam("query", "  하루들  ")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(activeUser.getId().toString()))
                .andExpect(jsonPath("$.content[0].name").value("하루들이"))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.content[0].lastLoginAt").isNotEmpty())
                .andExpect(jsonPath("$.content[0].generationUsage.usageDate")
                        .value(LocalDate.now(SERVICE_ZONE).toString()))
                .andExpect(jsonPath("$.content[0].generationUsage.usedCount").value(2))
                .andExpect(jsonPath("$.content[0].generationUsage.limitCount").value(3))
                .andExpect(jsonPath("$.content[0].generationUsage.remainingCount").value(1))
                .andExpect(jsonPath("$.content[0].email").doesNotExist())
                .andExpect(jsonPath("$.content[1].id").value(deletedUser.getId().toString()))
                .andExpect(jsonPath("$.content[1].status").value("DELETED"))
                .andExpect(jsonPath("$.content[1].generationUsage.usedCount").value(0))
                .andExpect(jsonPath("$.content[1].generationUsage.limitCount").value(3))
                .andExpect(jsonPath("$.content[1].generationUsage.remainingCount").value(3))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("검색 결과가 없으면 빈 페이지 메타데이터를 반환한다")
    void returnsEmptyPageWhenNoUserMatches() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);

        mockMvc.perform(get("/api/v1/admin/users")
                        .queryParam("query", "없는 사용자")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("페이지 크기가 범위를 벗어나면 검증 오류를 반환한다")
    void rejectsInvalidPageSize() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);

        mockMvc.perform(get("/api/v1/admin/users")
                        .queryParam("size", "101")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/admin/users")
                        .queryParam("page", "21474837")
                        .queryParam("size", "100")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("사용자 상세 조회는 사용자별 한도와 삭제된 일기의 최근 생성 이력을 반환한다")
    void findsUserDetailWithUsageAndRecentGenerations() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User target = saveUser("상세 사용자");
        changeDailyGenerationLimit(target, 5);
        saveOAuthAccount(target);
        GenerationPrompt prompt = generationPromptRepository.save(new GenerationPrompt(
                "상세 조회 테스트 스토리보드",
                "상세 조회 테스트 이미지 스타일",
                "references/admin-detail.png"
        ));

        saveGeneration(target, prompt, CREATED_AT, "SUCCEEDED", null, false);
        UUID deletedGenerationId = saveGeneration(
                target,
                prompt,
                CREATED_AT.plusSeconds(1),
                "FAILED",
                "AI_PROVIDER_ERROR",
                true
        );
        saveGeneration(target, prompt, CREATED_AT.plusSeconds(2), "PROCESSING", null, false);
        saveGeneration(target, prompt, CREATED_AT.plusSeconds(3), "SUCCEEDED", null, false);
        saveGeneration(
                target,
                prompt,
                CREATED_AT.plusSeconds(4),
                "FAILED",
                "IMAGE_STORAGE_ERROR",
                false
        );
        UUID newestGenerationId = saveGeneration(
                target,
                prompt,
                CREATED_AT.plusSeconds(5),
                "PROCESSING",
                null,
                false
        );

        mockMvc.perform(get("/api/v1/admin/users/{userId}", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(target.getId().toString()))
                .andExpect(jsonPath("$.name").value("상세 사용자"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.lastLoginAt").value(CREATED_AT.plusSeconds(10).toString()))
                .andExpect(jsonPath("$.generationUsage.usageDate")
                        .value(LocalDate.now(SERVICE_ZONE).toString()))
                .andExpect(jsonPath("$.generationUsage.usedCount").value(0))
                .andExpect(jsonPath("$.generationUsage.limitCount").value(5))
                .andExpect(jsonPath("$.generationUsage.remainingCount").value(5))
                .andExpect(jsonPath("$.recentGenerations.length()").value(5))
                .andExpect(jsonPath("$.recentGenerations[0].id")
                        .value(newestGenerationId.toString()))
                .andExpect(jsonPath("$.recentGenerations[0].requestedAt")
                        .value(CREATED_AT.plusSeconds(5).toString()))
                .andExpect(jsonPath("$.recentGenerations[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$.recentGenerations[0].completedAt").doesNotExist())
                .andExpect(jsonPath("$.recentGenerations[1].status").value("FAILED"))
                .andExpect(jsonPath("$.recentGenerations[1].errorCode")
                        .value("IMAGE_STORAGE_ERROR"))
                .andExpect(jsonPath("$.recentGenerations[2].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.recentGenerations[3].status").value("PROCESSING"))
                .andExpect(jsonPath("$.recentGenerations[4].id")
                        .value(deletedGenerationId.toString()))
                .andExpect(jsonPath("$.recentGenerations[4].errorCode")
                        .value("AI_PROVIDER_ERROR"))
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    @DisplayName("탈퇴 사용자의 상세 정보는 조회할 수 있다")
    void findsDeletedUserDetail() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User deletedUser = saveUser("탈퇴 사용자");
        markDeleted(deletedUser);

        mockMvc.perform(get("/api/v1/admin/users/{userId}", deletedUser.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(deletedUser.getId().toString()))
                .andExpect(jsonPath("$.status").value("DELETED"));
    }

    @Test
    @DisplayName("없는 사용자와 게스트 사용자의 상세 조회는 사용자 없음 오류를 반환한다")
    void rejectsMissingAndGuestUserDetail() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User guestUser = saveUser("게스트 사용자");
        saveGuestSession(guestUser);

        mockMvc.perform(get("/api/v1/admin/users/{userId}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/admin/users/{userId}", guestUser.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("게스트 사용자의 생성 사용량 변경은 거부한다")
    void rejectsGenerationUsageOperationsForGuestUser() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User guestUser = saveUser("게스트 사용자");
        saveGuestSession(guestUser);
        saveTodayUsage(guestUser, 2, 3);

        mockMvc.perform(put("/api/v1/admin/users/{userId}/generation-limit", guestUser.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limitCount\":5}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        mockMvc.perform(put("/api/v1/admin/users/{userId}/generation-usage/reset", guestUser.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        mockMvc.perform(patch(
                        "/api/v1/admin/users/{userId}/generation-usage/restore",
                        guestUser.getId()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT daily_generation_limit FROM users WHERE id = ?",
                Integer.class,
                guestUser.getId()
        )).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT used_count FROM daily_generation_usage WHERE user_id = ? AND usage_date = ?",
                Integer.class,
                guestUser.getId(),
                LocalDate.now(SERVICE_ZONE)
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT limit_count FROM daily_generation_usage WHERE user_id = ? AND usage_date = ?",
                Integer.class,
                guestUser.getId(),
                LocalDate.now(SERVICE_ZONE)
        )).isEqualTo(3);
    }

    @Test
    @DisplayName("관리자는 사용자의 오늘 생성 횟수를 지정한 수량만큼 복구할 수 있다")
    void restoresGenerationUsage() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User target = saveUser("복구 대상 사용자");
        saveTodayUsage(target, 3, 3);

        mockMvc.perform(patch("/api/v1/admin/users/{userId}/generation-usage/restore", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usageDate").value(LocalDate.now(SERVICE_ZONE).toString()))
                .andExpect(jsonPath("$.usedCount").value(1))
                .andExpect(jsonPath("$.limitCount").value(3))
                .andExpect(jsonPath("$.remainingCount").value(2));

        Integer usedCount = jdbcTemplate.queryForObject(
                "SELECT used_count FROM daily_generation_usage WHERE user_id = ? AND usage_date = ?",
                Integer.class,
                target.getId(),
                LocalDate.now(SERVICE_ZONE)
        );
        org.assertj.core.api.Assertions.assertThat(usedCount).isEqualTo(1);
    }

    @Test
    @DisplayName("사용량 행이 없거나 복구 수량이 많으면 충돌 오류를 반환한다")
    void rejectsConflictingGenerationUsageRestore() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User targetWithoutUsage = saveUser("사용량 없는 사용자");

        mockMvc.perform(patch(
                        "/api/v1/admin/users/{userId}/generation-usage/restore",
                        targetWithoutUsage.getId()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENERATION_USAGE_CONFLICT"));

        User targetWithInsufficientUsage = saveUser("사용량 부족 사용자");
        saveTodayUsage(targetWithInsufficientUsage, 1, 3);

        mockMvc.perform(patch(
                        "/api/v1/admin/users/{userId}/generation-usage/restore",
                        targetWithInsufficientUsage.getId()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENERATION_USAGE_CONFLICT"));
    }

    @Test
    @DisplayName("탈퇴 사용자의 생성 횟수 복구는 거부한다")
    void rejectsGenerationUsageRestoreForDeletedUser() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User deletedUser = saveUser("탈퇴 사용자");
        markDeleted(deletedUser);
        saveTodayUsage(deletedUser, 1, 3);

        mockMvc.perform(patch(
                        "/api/v1/admin/users/{userId}/generation-usage/restore",
                        deletedUser.getId()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INACTIVE_USER"));
    }

    @Test
    @DisplayName("복구 횟수가 1 미만이면 검증 오류를 반환한다")
    void rejectsInvalidGenerationUsageRestoreCount() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);

        mockMvc.perform(patch(
                        "/api/v1/admin/users/{userId}/generation-usage/restore",
                        admin.getId()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("관리자는 사용자의 오늘 생성 사용량을 0으로 초기화하고 반복 요청에도 같은 결과를 반환한다")
    void resetsGenerationUsageIdempotently() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User target = saveUser("초기화 대상 사용자");
        saveTodayUsage(target, 3, 5);

        mockMvc.perform(put("/api/v1/admin/users/{userId}/generation-usage/reset", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usageDate").value(LocalDate.now(SERVICE_ZONE).toString()))
                .andExpect(jsonPath("$.usedCount").value(0))
                .andExpect(jsonPath("$.limitCount").value(5))
                .andExpect(jsonPath("$.remainingCount").value(5));

        mockMvc.perform(put("/api/v1/admin/users/{userId}/generation-usage/reset", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedCount").value(0))
                .andExpect(jsonPath("$.limitCount").value(5))
                .andExpect(jsonPath("$.remainingCount").value(5));

        Integer usedCount = jdbcTemplate.queryForObject(
                "SELECT used_count FROM daily_generation_usage WHERE user_id = ? AND usage_date = ?",
                Integer.class,
                target.getId(),
                LocalDate.now(SERVICE_ZONE)
        );
        assertThat(usedCount).isZero();
    }

    @Test
    @DisplayName("사용량 행이 없으면 사용자별 한도로 기본 사용량을 반환하고 행을 만들지 않는다")
    void returnsCurrentLimitWithoutCreatingUsageOnReset() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User target = saveUser("사용량 없는 사용자");
        changeDailyGenerationLimit(target, 7);

        mockMvc.perform(put("/api/v1/admin/users/{userId}/generation-usage/reset", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usageDate").value(LocalDate.now(SERVICE_ZONE).toString()))
                .andExpect(jsonPath("$.usedCount").value(0))
                .andExpect(jsonPath("$.limitCount").value(7))
                .andExpect(jsonPath("$.remainingCount").value(7));

        Integer usageRowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM daily_generation_usage WHERE user_id = ? AND usage_date = ?",
                Integer.class,
                target.getId(),
                LocalDate.now(SERVICE_ZONE)
        );
        assertThat(usageRowCount).isZero();
    }

    @Test
    @DisplayName("탈퇴했거나 없는 사용자의 생성 사용량 초기화는 거부한다")
    void rejectsInvalidGenerationUsageResetTarget() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User deletedUser = saveUser("탈퇴 사용자");
        markDeleted(deletedUser);

        mockMvc.perform(put("/api/v1/admin/users/{userId}/generation-usage/reset", deletedUser.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INACTIVE_USER"));

        mockMvc.perform(put("/api/v1/admin/users/{userId}/generation-usage/reset", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("관리자는 사용자의 생성 한도를 변경하고 오늘 사용량 스냅샷도 갱신한다")
    void changesGenerationLimitAndTodaySnapshot() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User target = saveUser("한도 변경 대상 사용자");
        saveTodayUsage(target, 2, 3);

        mockMvc.perform(put("/api/v1/admin/users/{userId}/generation-limit", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limitCount\":5}"))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT daily_generation_limit FROM users WHERE id = ?",
                Integer.class,
                target.getId()
        )).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT used_count FROM daily_generation_usage WHERE user_id = ? AND usage_date = ?",
                Integer.class,
                target.getId(),
                LocalDate.now(SERVICE_ZONE)
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT limit_count FROM daily_generation_usage WHERE user_id = ? AND usage_date = ?",
                Integer.class,
                target.getId(),
                LocalDate.now(SERVICE_ZONE)
        )).isEqualTo(5);
    }

    @Test
    @DisplayName("새 한도가 오늘 사용량보다 작으면 오늘 스냅샷을 사용량까지 유지한다")
    void keepsTodaySnapshotAtUsedCountWhenLimitDecreases() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User target = saveUser("한도 축소 대상 사용자");
        saveTodayUsage(target, 2, 3);

        mockMvc.perform(put("/api/v1/admin/users/{userId}/generation-limit", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limitCount\":1}"))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT daily_generation_limit FROM users WHERE id = ?",
                Integer.class,
                target.getId()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT used_count FROM daily_generation_usage WHERE user_id = ? AND usage_date = ?",
                Integer.class,
                target.getId(),
                LocalDate.now(SERVICE_ZONE)
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT limit_count FROM daily_generation_usage WHERE user_id = ? AND usage_date = ?",
                Integer.class,
                target.getId(),
                LocalDate.now(SERVICE_ZONE)
        )).isEqualTo(2);
    }

    @Test
    @DisplayName("오늘 사용량 행이 없어도 생성 한도만 변경하고 사용량 행은 만들지 않는다")
    void changesGenerationLimitWithoutCreatingUsage() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User target = saveUser("사용량 없는 사용자");

        mockMvc.perform(put("/api/v1/admin/users/{userId}/generation-limit", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limitCount\":7}"))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT daily_generation_limit FROM users WHERE id = ?",
                Integer.class,
                target.getId()
        )).isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM daily_generation_usage WHERE user_id = ? AND usage_date = ?",
                Integer.class,
                target.getId(),
                LocalDate.now(SERVICE_ZONE)
        )).isZero();
    }

    @Test
    @DisplayName("잘못된 생성 한도 변경 요청은 거부한다")
    void rejectsInvalidGenerationLimitChange() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User deletedUser = saveUser("탈퇴 사용자");
        markDeleted(deletedUser);

        mockMvc.perform(put("/api/v1/admin/users/{userId}/generation-limit", deletedUser.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limitCount\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INACTIVE_USER"));

        mockMvc.perform(put("/api/v1/admin/users/{userId}/generation-limit", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limitCount\":5}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        mockMvc.perform(put("/api/v1/admin/users/{userId}/generation-limit", admin.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limitCount\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(put("/api/v1/admin/users/{userId}/generation-limit", admin.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limitCount\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private User saveUser(String name) {
        return saveUser(name, CREATED_AT);
    }

    private User saveUser(String name, Instant createdAt) {
        return userRepository.save(new User(null, name, createdAt));
    }

    private void grantAdminRole(User user) {
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user.getId());
    }

    private void markDeleted(User user) {
        jdbcTemplate.update(
                "UPDATE users SET deleted_at = ? WHERE id = ?",
                Timestamp.from(CREATED_AT.plusSeconds(1)),
                user.getId()
        );
    }

    private void saveOAuthAccount(User user) {
        oauthAccountRepository.save(new OAuthAccount(
                user,
                OAuthProvider.KAKAO,
                "search-user",
                null,
                CREATED_AT.plusSeconds(10)
        ));
    }

    private void saveTodayUsage(User user, int usedCount, int limitCount) {
        jdbcTemplate.update("""
                INSERT INTO daily_generation_usage(user_id, usage_date, used_count, limit_count)
                VALUES (?, ?, ?, ?)
                """, user.getId(), LocalDate.now(SERVICE_ZONE), usedCount, limitCount);
    }

    private void changeDailyGenerationLimit(User user, int limitCount) {
        jdbcTemplate.update(
                "UPDATE users SET daily_generation_limit = ? WHERE id = ?",
                limitCount,
                user.getId()
        );
    }

    private UUID saveGeneration(
            User user,
            GenerationPrompt prompt,
            Instant createdAt,
            String status,
            String errorCode,
            boolean deletedDiary
    ) {
        UUID diaryId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO diaries (
                    id, user_id, diary_date, source_text, created_at, updated_at, deleted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, diaryId, user.getId(), createdAt.atZone(SERVICE_ZONE).toLocalDate(),
                "상세 조회 테스트 일기", Timestamp.from(createdAt), Timestamp.from(createdAt),
                deletedDiary ? Timestamp.from(createdAt.plusSeconds(1)) : null);

        UUID generationId = UUID.randomUUID();
        boolean completed = !"PROCESSING".equals(status);
        jdbcTemplate.update("""
                INSERT INTO diary_generations (
                    id,
                    diary_id,
                    prompt_id,
                    idempotency_key,
                    request_fingerprint,
                    status,
                    title,
                    image_object_key,
                    error_code,
                    created_at,
                    updated_at,
                    completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, generationId, diaryId, prompt.getId(), UUID.randomUUID(), "a".repeat(64), status,
                "SUCCEEDED".equals(status) ? "상세 조회 테스트 생성" : null,
                "SUCCEEDED".equals(status) ? "generated/admin-detail/" + generationId + ".png" : null,
                errorCode, Timestamp.from(createdAt), Timestamp.from(createdAt),
                completed ? Timestamp.from(createdAt.plusSeconds(1)) : null);
        return generationId;
    }

    private void saveGuestSession(User user) {
        jdbcTemplate.update("""
                INSERT INTO guest_sessions(
                    id, guest_user_id, token_hash, expires_at, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), user.getId(), "a".repeat(64),
                Timestamp.from(CREATED_AT.plusSeconds(3600)),
                Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));
    }

    private String bearerToken(User user) {
        return "Bearer " + accessTokenService.issue(user.getId(), Instant.now()).accessToken();
    }
}
