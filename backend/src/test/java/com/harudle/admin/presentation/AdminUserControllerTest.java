package com.harudle.admin.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.support.MockMvcIntegrationTest;
import com.harudle.auth.application.AccessTokenService;
import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.generation.prompt.domain.GenerationPrompt;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import io.restassured.http.ContentType;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
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
class AdminUserControllerTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-25T01:00:00Z");
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final String IDEMPOTENCY_KEY = "b7a8a9aa-0b1c-4d2e-8f3a-4b5c6d7e8f90";
    private static final String ANOTHER_IDEMPOTENCY_KEY = "c8b9baab-1c2d-4e3f-9a4b-5c6d7e8f9012";

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

        MockMvcResponse response = adminRequest(admin)
                .queryParam("query", "  하루들  ")
                .get("/api/v1/admin/users");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getList("content")).hasSize(2);
        assertThat(response.jsonPath().getString("content[0].id"))
                .isEqualTo(activeUser.getId().toString());
        assertThat(response.jsonPath().getString("content[0].name")).isEqualTo("하루들이");
        assertThat(response.jsonPath().getString("content[0].status")).isEqualTo("ACTIVE");
        assertThat(response.jsonPath().getString("content[0].lastLoginAt")).isNotNull();
        assertThat(response.jsonPath().getString("content[0].generationUsage.usageDate"))
                .isEqualTo(LocalDate.now(SERVICE_ZONE).toString());
        assertThat(response.jsonPath().getInt("content[0].generationUsage.usedCount"))
                .isEqualTo(2);
        assertThat(response.jsonPath().getInt("content[0].generationUsage.limitCount"))
                .isEqualTo(3);
        assertThat(response.jsonPath().getInt("content[0].generationUsage.remainingCount"))
                .isEqualTo(1);
        assertThat(response.jsonPath().getString("content[0].email")).isNull();
        assertThat(response.jsonPath().getString("content[1].id"))
                .isEqualTo(deletedUser.getId().toString());
        assertThat(response.jsonPath().getString("content[1].status")).isEqualTo("DELETED");
        assertThat(response.jsonPath().getInt("content[1].generationUsage.usedCount"))
                .isZero();
        assertThat(response.jsonPath().getInt("content[1].generationUsage.limitCount"))
                .isEqualTo(3);
        assertThat(response.jsonPath().getInt("content[1].generationUsage.remainingCount"))
                .isEqualTo(3);
        assertThat(response.jsonPath().getInt("totalElements")).isEqualTo(2);
        assertThat(response.jsonPath().getInt("totalPages")).isEqualTo(1);
        assertThat(response.jsonPath().getBoolean("hasNext")).isFalse();
    }

    @Test
    @DisplayName("검색 결과가 없으면 빈 페이지 메타데이터를 반환한다")
    void returnsEmptyPageWhenNoUserMatches() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);

        MockMvcResponse response = adminRequest(admin)
                .queryParam("query", "없는 사용자")
                .get("/api/v1/admin/users");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getList("content")).isEmpty();
        assertThat(response.jsonPath().getInt("totalElements")).isZero();
        assertThat(response.jsonPath().getInt("totalPages")).isZero();
        assertThat(response.jsonPath().getBoolean("hasNext")).isFalse();
    }

    @Test
    @DisplayName("페이지 크기가 범위를 벗어나면 검증 오류를 반환한다")
    void rejectsInvalidPageSize() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);

        MockMvcResponse response = adminRequest(admin)
                .queryParam("size", "101")
                .get("/api/v1/admin/users");
        assertValidationError(response);

        response = adminRequest(admin)
                .queryParam("page", "21474837")
                .queryParam("size", "100")
                .get("/api/v1/admin/users");
        assertValidationError(response);
    }

    @Test
    @DisplayName("닉네임을 반환하고 닉네임 일부로 대소문자 구분 없이 검색한다")
    void searchesUsersByNickname() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User nicknamed = saveUser("실명 사용자");
        changeNickname(nicknamed, "SunnyHaru");
        saveUser("닉네임 없는 사용자");

        MockMvcResponse response = adminRequest(admin)
                .queryParam("query", "sunny")
                .get("/api/v1/admin/users");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getList("content.id"))
                .containsExactly(nicknamed.getId().toString());
        assertThat(response.jsonPath().getString("content[0].name")).isEqualTo("실명 사용자");
        assertThat(response.jsonPath().getString("content[0].nickname")).isEqualTo("SunnyHaru");
        assertThat(response.jsonPath().getInt("totalElements")).isEqualTo(1);

        response = adminRequest(admin)
                .queryParam("query", "닉네임 없는")
                .get("/api/v1/admin/users");

        assertThat(response.jsonPath().getList("content")).hasSize(1);
        assertThat(response.jsonPath().getString("content[0].nickname")).isNull();
    }

    @Test
    @DisplayName("생성한 일기 수는 삭제한 일기를 포함한 생성 성공 일기만 센다")
    void countsSucceededDiariesIncludingDeleted() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User writer = saveUser("일기 작성자");
        User newcomer = saveUser("일기 없는 사용자", CREATED_AT.minusSeconds(1));
        GenerationPrompt prompt = generationPromptRepository.save(new GenerationPrompt(
                "생성 수 테스트 스토리보드",
                "생성 수 테스트 이미지 스타일",
                "references/admin-count.png"
        ));
        saveGeneration(writer, prompt, CREATED_AT, "SUCCEEDED", null, false);
        saveGeneration(writer, prompt, CREATED_AT.plusSeconds(1), "SUCCEEDED", null, true);
        saveGeneration(writer, prompt, CREATED_AT.plusSeconds(2), "FAILED", "AI_PROVIDER_ERROR", false);
        saveGeneration(writer, prompt, CREATED_AT.plusSeconds(3), "FAILED", "AI_PROVIDER_ERROR", true);
        saveGeneration(writer, prompt, CREATED_AT.plusSeconds(4), "PROCESSING", null, false);

        MockMvcResponse response = adminRequest(admin)
                .queryParam("query", "일기")
                .get("/api/v1/admin/users");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getList("content.id")).containsExactly(
                writer.getId().toString(),
                newcomer.getId().toString()
        );
        assertThat(response.jsonPath().getLong("content[0].generatedDiaryCount")).isEqualTo(2);
        assertThat(response.jsonPath().getLong("content[1].generatedDiaryCount")).isZero();
    }

    @Test
    @DisplayName("이름, 가입일, 오늘 남은 생성 횟수로 정렬하고 같은 값은 사용자 ID로 정렬한다")
    void sortsUsersByRequestedKeyWithIdTiebreaker() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User third = saveUser("정렬다", CREATED_AT);
        User first = saveUser("정렬가", CREATED_AT.plusSeconds(2));
        saveTodayUsage(first, 2, 3);
        User tiedWithoutUsage = saveUser("정렬나", CREATED_AT.plusSeconds(1));
        changeDailyGenerationLimit(tiedWithoutUsage, 5);
        User tiedWithUsage = saveUser("정렬나", CREATED_AT.plusSeconds(1));
        saveTodayUsage(tiedWithUsage, 0, 5);
        List<String> tiedAsc = Stream.of(tiedWithoutUsage, tiedWithUsage)
                .map(user -> user.getId().toString())
                .sorted()
                .toList();
        List<String> tiedDesc = tiedAsc.reversed();
        String a = third.getId().toString();
        String b = first.getId().toString();

        assertSortedIds(admin, "NAME", "ASC", concat(List.of(b), tiedAsc, List.of(a)));
        assertSortedIds(admin, "NAME", "DESC", concat(List.of(a), tiedDesc, List.of(b)));
        assertSortedIds(admin, "CREATED_AT", "ASC", concat(List.of(a), tiedAsc, List.of(b)));
        assertSortedIds(admin, "CREATED_AT", "DESC", concat(List.of(b), tiedDesc, List.of(a)));
        assertSortedIds(admin, "REMAINING_COUNT", "ASC", concat(List.of(b, a), tiedAsc));
        assertSortedIds(admin, "REMAINING_COUNT", "DESC", concat(tiedDesc, List.of(a, b)));

        MockMvcResponse response = adminRequest(admin)
                .queryParam("query", "정렬")
                .queryParam("sort", "REMAINING_COUNT")
                .queryParam("direction", "ASC")
                .get("/api/v1/admin/users");
        assertThat(response.jsonPath().getList("content.generationUsage.remainingCount", Integer.class))
                .containsExactly(1, 3, 5, 5);
    }

    @Test
    @DisplayName("남은 생성 횟수 정렬 순서는 응답의 남은 횟수와 같은 방향으로 단조롭다")
    void sortsByRemainingCountConsistentlyWithResponse() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User withoutUsageLow = saveUser("잔여정렬");
        changeDailyGenerationLimit(withoutUsageLow, 2);
        User withoutUsageHigh = saveUser("잔여정렬");
        changeDailyGenerationLimit(withoutUsageHigh, 3);
        User snapshotAboveLimit = saveUser("잔여정렬");
        changeDailyGenerationLimit(snapshotAboveLimit, 3);
        saveTodayUsage(snapshotAboveLimit, 1, 6);
        User exhausted = saveUser("잔여정렬");
        changeDailyGenerationLimit(exhausted, 10);
        saveTodayUsage(exhausted, 4, 4);
        User snapshotBelowLimit = saveUser("잔여정렬");
        changeDailyGenerationLimit(snapshotBelowLimit, 7);
        saveTodayUsage(snapshotBelowLimit, 0, 1);

        List<Integer> ascending = remainingCounts(admin, "ASC");
        List<Integer> descending = remainingCounts(admin, "DESC");

        assertThat(ascending).containsExactlyInAnyOrder(0, 1, 2, 3, 5).isSorted();
        assertThat(descending).containsExactlyInAnyOrder(0, 1, 2, 3, 5)
                .isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    @DisplayName("알 수 없는 정렬 기준이나 방향은 검증 오류를 반환한다")
    void rejectsUnknownSort() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);

        assertValidationError(adminRequest(admin)
                .queryParam("sort", "EMAIL")
                .get("/api/v1/admin/users"));
        assertValidationError(adminRequest(admin)
                .queryParam("sort", "NAME")
                .queryParam("direction", "SIDEWAYS")
                .get("/api/v1/admin/users"));
        assertValidationError(adminRequest(admin)
                .queryParam("sort", "name")
                .get("/api/v1/admin/users"));
        assertValidationError(adminRequest(admin)
                .queryParam("sort", "NAME")
                .queryParam("direction", "desc")
                .get("/api/v1/admin/users"));
    }

    @Test
    @DisplayName("사용자 상세 조회는 사용자별 한도와 삭제된 일기의 최근 생성 이력을 반환한다")
    void findsUserDetailWithUsageAndRecentGenerations() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User target = saveUser("상세 사용자");
        changeNickname(target, "DetailHaru");
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

        MockMvcResponse response = adminRequest(admin)
                .get("/api/v1/admin/users/{userId}", target.getId());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("id")).isEqualTo(target.getId().toString());
        assertThat(response.jsonPath().getString("name")).isEqualTo("상세 사용자");
        assertThat(response.jsonPath().getString("nickname")).isEqualTo("DetailHaru");
        assertThat(response.jsonPath().getString("status")).isEqualTo("ACTIVE");
        assertThat(response.jsonPath().getString("lastLoginAt"))
                .isEqualTo(CREATED_AT.plusSeconds(10).toString());
        assertThat(response.jsonPath().getString("generationUsage.usageDate"))
                .isEqualTo(LocalDate.now(SERVICE_ZONE).toString());
        assertThat(response.jsonPath().getInt("generationUsage.usedCount")).isZero();
        assertThat(response.jsonPath().getInt("generationUsage.limitCount")).isEqualTo(5);
        assertThat(response.jsonPath().getInt("generationUsage.remainingCount")).isEqualTo(5);
        assertThat(response.jsonPath().getList("recentGenerations")).hasSize(5);
        assertThat(response.jsonPath().getString("recentGenerations[0].id"))
                .isEqualTo(newestGenerationId.toString());
        assertThat(response.jsonPath().getString("recentGenerations[0].requestedAt"))
                .isEqualTo(CREATED_AT.plusSeconds(5).toString());
        assertThat(response.jsonPath().getString("recentGenerations[0].status"))
                .isEqualTo("PROCESSING");
        assertThat(response.jsonPath().getString("recentGenerations[0].completedAt")).isNull();
        assertThat(response.jsonPath().getString("recentGenerations[1].status"))
                .isEqualTo("FAILED");
        assertThat(response.jsonPath().getString("recentGenerations[1].errorCode"))
                .isEqualTo("IMAGE_STORAGE_ERROR");
        assertThat(response.jsonPath().getString("recentGenerations[2].status"))
                .isEqualTo("SUCCEEDED");
        assertThat(response.jsonPath().getString("recentGenerations[3].status"))
                .isEqualTo("PROCESSING");
        assertThat(response.jsonPath().getString("recentGenerations[4].id"))
                .isEqualTo(deletedGenerationId.toString());
        assertThat(response.jsonPath().getString("recentGenerations[4].errorCode"))
                .isEqualTo("AI_PROVIDER_ERROR");
        assertThat(response.jsonPath().getString("email")).isNull();
    }

    @Test
    @DisplayName("탈퇴 사용자의 상세 정보는 조회할 수 있다")
    void findsDeletedUserDetail() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User deletedUser = saveUser("탈퇴 사용자");
        markDeleted(deletedUser);

        MockMvcResponse response = adminRequest(admin)
                .get("/api/v1/admin/users/{userId}", deletedUser.getId());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("id")).isEqualTo(deletedUser.getId().toString());
        assertThat(response.jsonPath().getMap("")).containsEntry("nickname", null);
        assertThat(response.jsonPath().getString("status")).isEqualTo("DELETED");
    }

    @Test
    @DisplayName("없는 사용자와 게스트 사용자의 상세 조회는 사용자 없음 오류를 반환한다")
    void rejectsMissingAndGuestUserDetail() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User guestUser = saveUser("게스트 사용자");
        saveGuestSession(guestUser);

        MockMvcResponse response = adminRequest(admin)
                .get("/api/v1/admin/users/{userId}", UUID.randomUUID());
        assertResponseCode(response, 404, "USER_NOT_FOUND");

        response = adminRequest(admin)
                .get("/api/v1/admin/users/{userId}", guestUser.getId());
        assertResponseCode(response, 404, "USER_NOT_FOUND");
    }

    @Test
    @DisplayName("게스트 사용자의 생성 사용량 변경은 거부한다")
    void rejectsGenerationUsageOperationsForGuestUser() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User guestUser = saveUser("게스트 사용자");
        saveGuestSession(guestUser);
        saveTodayUsage(guestUser, 2, 3);

        MockMvcResponse response = adminRequest(admin)
                .contentType(ContentType.JSON)
                .body("{\"limitCount\":5}")
                .put("/api/v1/admin/users/{userId}/generation-limit", guestUser.getId());
        assertResponseCode(response, 404, "USER_NOT_FOUND");

        response = adminRequest(admin)
                .put("/api/v1/admin/users/{userId}/generation-usage/reset", guestUser.getId());
        assertResponseCode(response, 404, "USER_NOT_FOUND");

        response = adminRequest(admin)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(ContentType.JSON)
                .body("{\"count\":1}")
                .patch("/api/v1/admin/users/{userId}/generation-usage/restore", guestUser.getId());
        assertResponseCode(response, 404, "USER_NOT_FOUND");

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

        MockMvcResponse response = adminRequest(admin)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(ContentType.JSON)
                .body("{\"count\":2}")
                .patch("/api/v1/admin/users/{userId}/generation-usage/restore", target.getId());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("usageDate"))
                .isEqualTo(LocalDate.now(SERVICE_ZONE).toString());
        assertThat(response.jsonPath().getInt("usedCount")).isEqualTo(1);
        assertThat(response.jsonPath().getInt("limitCount")).isEqualTo(3);
        assertThat(response.jsonPath().getInt("remainingCount")).isEqualTo(2);

        Integer usedCount = jdbcTemplate.queryForObject(
                "SELECT used_count FROM daily_generation_usage WHERE user_id = ? AND usage_date = ?",
                Integer.class,
                target.getId(),
                LocalDate.now(SERVICE_ZONE)
        );
        assertThat(usedCount).isEqualTo(1);

        response = adminRequest(admin)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(ContentType.JSON)
                .body("{\"count\":2}")
                .patch("/api/v1/admin/users/{userId}/generation-usage/restore", target.getId());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("usageDate"))
                .isEqualTo(LocalDate.now(SERVICE_ZONE).toString());
        assertThat(response.jsonPath().getInt("usedCount")).isEqualTo(1);
        assertThat(response.jsonPath().getInt("limitCount")).isEqualTo(3);
        assertThat(response.jsonPath().getInt("remainingCount")).isEqualTo(2);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT used_count FROM daily_generation_usage WHERE user_id = ? AND usage_date = ?",
                Integer.class,
                target.getId(),
                LocalDate.now(SERVICE_ZONE)
        )).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 멱등성 키를 다른 복구 요청에 사용하면 거부한다")
    void rejectsReuseOfIdempotencyKeyForDifferentRestore() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User target = saveUser("복구 대상 사용자");
        saveTodayUsage(target, 3, 3);

        MockMvcResponse response = adminRequest(admin)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(ContentType.JSON)
                .body("{\"count\":1}")
                .patch("/api/v1/admin/users/{userId}/generation-usage/restore", target.getId());
        assertThat(response.statusCode()).isEqualTo(200);

        response = adminRequest(admin)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(ContentType.JSON)
                .body("{\"count\":2}")
                .patch("/api/v1/admin/users/{userId}/generation-usage/restore", target.getId());
        assertResponseCode(response, 409, "IDEMPOTENCY_KEY_CONFLICT");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT used_count FROM daily_generation_usage WHERE user_id = ? AND usage_date = ?",
                Integer.class,
                target.getId(),
                LocalDate.now(SERVICE_ZONE)
        )).isEqualTo(2);
    }

    @Test
    @DisplayName("멱등성 키가 없으면 사용량 복구를 거부한다")
    void rejectsMissingIdempotencyKeyForGenerationUsageRestore() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User target = saveUser("복구 대상 사용자");
        saveTodayUsage(target, 3, 3);

        MockMvcResponse response = adminRequest(admin)
                .contentType(ContentType.JSON)
                .body("{\"count\":1}")
                .patch("/api/v1/admin/users/{userId}/generation-usage/restore", target.getId());
        assertResponseCode(response, 400, "INVALID_IDEMPOTENCY_KEY");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT used_count FROM daily_generation_usage WHERE user_id = ? AND usage_date = ?",
                Integer.class,
                target.getId(),
                LocalDate.now(SERVICE_ZONE)
        )).isEqualTo(3);
    }

    @Test
    @DisplayName("사용량 행이 없거나 복구 수량이 많으면 충돌 오류를 반환한다")
    void rejectsConflictingGenerationUsageRestore() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User targetWithoutUsage = saveUser("사용량 없는 사용자");

        MockMvcResponse response = adminRequest(admin)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(ContentType.JSON)
                .body("{\"count\":1}")
                .patch("/api/v1/admin/users/{userId}/generation-usage/restore", targetWithoutUsage.getId());
        assertResponseCode(response, 409, "GENERATION_USAGE_CONFLICT");

        saveTodayUsage(targetWithoutUsage, 1, 3);

        response = adminRequest(admin)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(ContentType.JSON)
                .body("{\"count\":1}")
                .patch("/api/v1/admin/users/{userId}/generation-usage/restore", targetWithoutUsage.getId());
        assertResponseCode(response, 409, "GENERATION_USAGE_CONFLICT");

        User targetWithInsufficientUsage = saveUser("사용량 부족 사용자");
        saveTodayUsage(targetWithInsufficientUsage, 1, 3);

        response = adminRequest(admin)
                .header("Idempotency-Key", ANOTHER_IDEMPOTENCY_KEY)
                .contentType(ContentType.JSON)
                .body("{\"count\":2}")
                .patch("/api/v1/admin/users/{userId}/generation-usage/restore", targetWithInsufficientUsage.getId());
        assertResponseCode(response, 409, "GENERATION_USAGE_CONFLICT");
    }

    @Test
    @DisplayName("탈퇴 사용자의 생성 횟수 복구는 거부한다")
    void rejectsGenerationUsageRestoreForDeletedUser() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User deletedUser = saveUser("탈퇴 사용자");
        markDeleted(deletedUser);
        saveTodayUsage(deletedUser, 1, 3);

        MockMvcResponse response = adminRequest(admin)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(ContentType.JSON)
                .body("{\"count\":1}")
                .patch("/api/v1/admin/users/{userId}/generation-usage/restore", deletedUser.getId());
        assertResponseCode(response, 409, "INACTIVE_USER");
    }

    @Test
    @DisplayName("복구 횟수가 1 미만이면 검증 오류를 반환한다")
    void rejectsInvalidGenerationUsageRestoreCount() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);

        MockMvcResponse response = adminRequest(admin)
                .contentType(ContentType.JSON)
                .body("{\"count\":0}")
                .patch("/api/v1/admin/users/{userId}/generation-usage/restore", admin.getId());
        assertResponseCode(response, 400, "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("관리자는 사용자의 오늘 생성 사용량을 0으로 초기화하고 반복 요청에도 같은 결과를 반환한다")
    void resetsGenerationUsageIdempotently() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User target = saveUser("초기화 대상 사용자");
        changeDailyGenerationLimit(target, 5);
        saveTodayUsage(target, 3, 5);

        MockMvcResponse response = adminRequest(admin)
                .put("/api/v1/admin/users/{userId}/generation-usage/reset", target.getId());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("usageDate"))
                .isEqualTo(LocalDate.now(SERVICE_ZONE).toString());
        assertThat(response.jsonPath().getInt("usedCount")).isZero();
        assertThat(response.jsonPath().getInt("limitCount")).isEqualTo(5);
        assertThat(response.jsonPath().getInt("remainingCount")).isEqualTo(5);

        response = adminRequest(admin)
                .put("/api/v1/admin/users/{userId}/generation-usage/reset", target.getId());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getInt("usedCount")).isZero();
        assertThat(response.jsonPath().getInt("limitCount")).isEqualTo(5);
        assertThat(response.jsonPath().getInt("remainingCount")).isEqualTo(5);

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

        MockMvcResponse response = adminRequest(admin)
                .put("/api/v1/admin/users/{userId}/generation-usage/reset", target.getId());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("usageDate"))
                .isEqualTo(LocalDate.now(SERVICE_ZONE).toString());
        assertThat(response.jsonPath().getInt("usedCount")).isZero();
        assertThat(response.jsonPath().getInt("limitCount")).isEqualTo(7);
        assertThat(response.jsonPath().getInt("remainingCount")).isEqualTo(7);

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

        MockMvcResponse response = adminRequest(admin)
                .put("/api/v1/admin/users/{userId}/generation-usage/reset", deletedUser.getId());
        assertResponseCode(response, 409, "INACTIVE_USER");

        response = adminRequest(admin)
                .put("/api/v1/admin/users/{userId}/generation-usage/reset", UUID.randomUUID());
        assertResponseCode(response, 404, "USER_NOT_FOUND");
    }

    @Test
    @DisplayName("관리자는 사용자의 생성 한도를 변경하고 오늘 사용량 스냅샷도 갱신한다")
    void changesGenerationLimitAndTodaySnapshot() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User target = saveUser("한도 변경 대상 사용자");
        saveTodayUsage(target, 2, 3);

        MockMvcResponse response = adminRequest(admin)
                .contentType(ContentType.JSON)
                .body("{\"limitCount\":5}")
                .put("/api/v1/admin/users/{userId}/generation-limit", target.getId());
        assertThat(response.statusCode()).isEqualTo(204);

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
    @DisplayName("새 한도가 오늘 사용량보다 작으면 기존 한도를 유지하고 변경을 거부한다")
    void rejectsGenerationLimitBelowTodayUsage() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User target = saveUser("한도 축소 대상 사용자");
        saveTodayUsage(target, 2, 3);

        MockMvcResponse response = adminRequest(admin)
                .contentType(ContentType.JSON)
                .body("{\"limitCount\":1}")
                .put("/api/v1/admin/users/{userId}/generation-limit", target.getId());
        assertResponseCode(response, 409, "GENERATION_LIMIT_BELOW_USAGE");
        assertThat(response.jsonPath().getString("detail"))
                .isEqualTo("현재 사용량보다 작은 생성 한도로 변경할 수 없습니다.");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT daily_generation_limit FROM users WHERE id = ?",
                Integer.class,
                target.getId()
        )).isEqualTo(3);
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
        )).isEqualTo(3);
    }

    @Test
    @DisplayName("한도를 낮춘 뒤 사용량을 초기화하면 현재 한도로 오늘 스냅샷을 갱신한다")
    void resetsTodaySnapshotToCurrentLimitAfterLimitDecrease() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User target = saveUser("한도 초기화 대상 사용자");
        changeDailyGenerationLimit(target, 5);
        saveTodayUsage(target, 2, 5);

        MockMvcResponse response = adminRequest(admin)
                .contentType(ContentType.JSON)
                .body("{\"limitCount\":2}")
                .put("/api/v1/admin/users/{userId}/generation-limit", target.getId());
        assertThat(response.statusCode()).isEqualTo(204);

        response = adminRequest(admin)
                .put("/api/v1/admin/users/{userId}/generation-usage/reset", target.getId());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getInt("usedCount")).isZero();
        assertThat(response.jsonPath().getInt("limitCount")).isEqualTo(2);
        assertThat(response.jsonPath().getInt("remainingCount")).isEqualTo(2);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT daily_generation_limit FROM users WHERE id = ?",
                Integer.class,
                target.getId()
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT used_count FROM daily_generation_usage WHERE user_id = ? AND usage_date = ?",
                Integer.class,
                target.getId(),
                LocalDate.now(SERVICE_ZONE)
        )).isZero();
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

        MockMvcResponse response = adminRequest(admin)
                .contentType(ContentType.JSON)
                .body("{\"limitCount\":7}")
                .put("/api/v1/admin/users/{userId}/generation-limit", target.getId());
        assertThat(response.statusCode()).isEqualTo(204);

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

        MockMvcResponse response = adminRequest(admin)
                .contentType(ContentType.JSON)
                .body("{\"limitCount\":5}")
                .put("/api/v1/admin/users/{userId}/generation-limit", deletedUser.getId());
        assertResponseCode(response, 409, "INACTIVE_USER");

        response = adminRequest(admin)
                .contentType(ContentType.JSON)
                .body("{\"limitCount\":5}")
                .put("/api/v1/admin/users/{userId}/generation-limit", UUID.randomUUID());
        assertResponseCode(response, 404, "USER_NOT_FOUND");

        response = adminRequest(admin)
                .contentType(ContentType.JSON)
                .body("{\"limitCount\":-1}")
                .put("/api/v1/admin/users/{userId}/generation-limit", admin.getId());
        assertValidationError(response);

        response = adminRequest(admin)
                .contentType(ContentType.JSON)
                .body("{\"limitCount\":0}")
                .put("/api/v1/admin/users/{userId}/generation-limit", admin.getId());
        assertValidationError(response);
    }

    private void assertSortedIds(User admin, String sort, String direction, List<String> expectedIds) {
        MockMvcResponse response = adminRequest(admin)
                .queryParam("query", "정렬")
                .queryParam("sort", sort)
                .queryParam("direction", direction)
                .get("/api/v1/admin/users");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getList("content.id", String.class))
                .as("%s %s", sort, direction)
                .containsExactlyElementsOf(expectedIds);
    }

    private List<Integer> remainingCounts(User admin, String direction) {
        MockMvcResponse response = adminRequest(admin)
                .queryParam("query", "잔여정렬")
                .queryParam("sort", "REMAINING_COUNT")
                .queryParam("direction", direction)
                .get("/api/v1/admin/users");

        assertThat(response.statusCode()).isEqualTo(200);
        return response.jsonPath().getList("content.generationUsage.remainingCount", Integer.class);
    }

    @SafeVarargs
    private static List<String> concat(List<String>... parts) {
        return Stream.of(parts).flatMap(List::stream).toList();
    }

    private void changeNickname(User user, String nickname) {
        jdbcTemplate.update("UPDATE users SET nickname = ? WHERE id = ?", nickname, user.getId());
    }

    private void assertValidationError(MockMvcResponse response) {
        assertResponseCode(response, 400, "VALIDATION_ERROR");
    }

    private void assertResponseCode(MockMvcResponse response, int statusCode, String code) {
        assertThat(response.statusCode()).isEqualTo(statusCode);
        assertThat(response.jsonPath().getString("code")).isEqualTo(code);
    }

    private io.restassured.module.mockmvc.specification.MockMvcRequestSpecification adminRequest(
            User admin
    ) {
        return RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, bearerToken(admin));
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
        return "Bearer " + accessTokenService.issue(user.getId(), UUID.randomUUID(), Instant.now()).accessToken();
    }
}
