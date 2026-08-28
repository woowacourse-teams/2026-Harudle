package com.harudle.admin.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.auth.application.AccessTokenService;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.domain.GenerationPrompt;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.repository.GenerationPromptRepository;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class AdminGenerationControllerTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant CREATED_AT = Instant.parse("2026-08-06T00:00:00Z");

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
    private GenerationPromptRepository generationPromptRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private GenerationPrompt generationPrompt;

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.mockMvc(mockMvc);
        generationPrompt = generationPromptRepository.saveAndFlush(new GenerationPrompt(
                "관리자 생성 이력 스토리보드",
                "관리자 생성 이력 이미지 스타일",
                "references/admin-history-controller.png"
        ));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM diary_generations");
        jdbcTemplate.update("DELETE FROM diaries");
        userRepository.deleteAll();
        generationPromptRepository.deleteAll();
        RestAssuredMockMvc.reset();
    }

    @Test
    @DisplayName("전체 생성 이력과 사용자 정보를 최신순으로 조회한다")
    void findsAllGenerationHistory() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User firstUser = saveUser("첫 번째 사용자");
        User secondUser = saveUser("두 번째 사용자");
        UUID oldestGenerationId = saveGeneration(
                firstUser,
                CREATED_AT,
                GenerationStatus.FAILED,
                GenerationErrorCode.AI_PROVIDER_ERROR
        );
        UUID middleGenerationId = saveGeneration(
                firstUser,
                CREATED_AT.plusSeconds(1),
                GenerationStatus.SUCCEEDED,
                null
        );
        UUID newestGenerationId = saveGeneration(
                secondUser,
                CREATED_AT.plusSeconds(2),
                GenerationStatus.PROCESSING,
                null
        );

        MockMvcResponse response = adminRequest(admin)
                .get("/api/v1/admin/generations");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getList("content")).hasSize(3);
        assertThat(response.jsonPath().getString("content[0].id"))
                .isEqualTo(newestGenerationId.toString());
        assertThat(response.jsonPath().getString("content[0].user.id"))
                .isEqualTo(secondUser.getId().toString());
        assertThat(response.jsonPath().getString("content[0].user.name"))
                .isEqualTo("두 번째 사용자");
        assertThat(response.jsonPath().getString("content[0].user.email")).isNull();
        assertThat(response.jsonPath().getString("content[0].status")).isEqualTo("PROCESSING");
        assertThat(response.jsonPath().getString("content[0].completedAt")).isNull();
        assertThat(response.jsonPath().getString("content[0].errorCode")).isNull();
        assertThat(response.jsonPath().getString("content[1].id"))
                .isEqualTo(middleGenerationId.toString());
        assertThat(response.jsonPath().getString("content[1].status")).isEqualTo("SUCCEEDED");
        assertThat(response.jsonPath().getString("content[2].id"))
                .isEqualTo(oldestGenerationId.toString());
        assertThat(response.jsonPath().getString("content[2].status")).isEqualTo("FAILED");
        assertThat(response.jsonPath().getString("content[2].errorCode"))
                .isEqualTo("AI_PROVIDER_ERROR");
        assertThat(response.jsonPath().getInt("page")).isZero();
        assertThat(response.jsonPath().getInt("size")).isEqualTo(20);
        assertThat(response.jsonPath().getInt("totalElements")).isEqualTo(3);
        assertThat(response.jsonPath().getInt("totalPages")).isEqualTo(1);
        assertThat(response.jsonPath().getBoolean("hasNext")).isFalse();
    }

    @Test
    @DisplayName("실패 상태·사용자·기간 필터를 하나의 API에서 조합한다")
    void filtersFailedGenerationHistory() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);
        User target = saveUser("대상 사용자");
        User other = saveUser("다른 사용자");
        UUID targetFailedGenerationId = saveGeneration(
                target,
                Instant.parse("2026-08-06T01:00:00Z"),
                GenerationStatus.FAILED,
                GenerationErrorCode.IMAGE_STORAGE_ERROR
        );
        saveGeneration(
                target,
                Instant.parse("2026-08-06T02:00:00Z"),
                GenerationStatus.SUCCEEDED,
                null
        );
        saveGeneration(
                other,
                Instant.parse("2026-08-06T03:00:00Z"),
                GenerationStatus.FAILED,
                GenerationErrorCode.AI_PROVIDER_ERROR
        );

        MockMvcResponse response = adminRequest(admin)
                .queryParam("userId", target.getId().toString())
                .queryParam("status", "FAILED")
                .queryParam("from", "2026-08-06")
                .queryParam("to", "2026-08-06")
                .get("/api/v1/admin/generations");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getList("content")).hasSize(1);
        assertThat(response.jsonPath().getString("content[0].id"))
                .isEqualTo(targetFailedGenerationId.toString());
        assertThat(response.jsonPath().getString("content[0].user.name"))
                .isEqualTo("대상 사용자");
        assertThat(response.jsonPath().getString("content[0].errorCode"))
                .isEqualTo("IMAGE_STORAGE_ERROR");
        assertThat(response.jsonPath().getInt("totalElements")).isEqualTo(1);
    }

    @Test
    @DisplayName("생성 이력이 없으면 빈 페이지를 반환한다")
    void returnsEmptyHistoryPage() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);

        MockMvcResponse response = adminRequest(admin)
                .get("/api/v1/admin/generations");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getList("content")).isEmpty();
        assertThat(response.jsonPath().getInt("totalElements")).isZero();
        assertThat(response.jsonPath().getInt("totalPages")).isZero();
        assertThat(response.jsonPath().getBoolean("hasNext")).isFalse();
    }

    @Test
    @DisplayName("생성 이력 조회 파라미터가 잘못되면 검증 오류를 반환한다")
    void rejectsInvalidHistoryParameters() throws Exception {
        User admin = saveUser("관리자");
        grantAdminRole(admin);

        MockMvcResponse response = adminRequest(admin)
                .queryParam("userId", "not-a-uuid")
                .get("/api/v1/admin/generations");
        assertValidationError(response);

        response = adminRequest(admin)
                .queryParam("status", "UNKNOWN")
                .get("/api/v1/admin/generations");
        assertValidationError(response);

        response = adminRequest(admin)
                .queryParam("from", "2026-08-07")
                .queryParam("to", "2026-08-06")
                .get("/api/v1/admin/generations");
        assertValidationError(response);

        response = adminRequest(admin)
                .queryParam("to", LocalDate.MAX.toString())
                .get("/api/v1/admin/generations");
        assertValidationError(response);

        response = adminRequest(admin)
                .queryParam("size", "101")
                .get("/api/v1/admin/generations");
        assertValidationError(response);

        response = adminRequest(admin)
                .queryParam("page", "21474837")
                .queryParam("size", "100")
                .get("/api/v1/admin/generations");
        assertValidationError(response);
    }

    private User saveUser(String name) {
        return userRepository.saveAndFlush(new User(null, name, CREATED_AT));
    }

    private void grantAdminRole(User user) {
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user.getId());
    }

    private void assertValidationError(MockMvcResponse response) {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    }

    private io.restassured.module.mockmvc.specification.MockMvcRequestSpecification adminRequest(
            User admin
    ) {
        return RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, bearerToken(admin));
    }

    private UUID saveGeneration(
            User user,
            Instant requestedAt,
            GenerationStatus status,
            GenerationErrorCode errorCode
    ) {
        UUID diaryId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO diaries (
                    id, user_id, diary_date, source_text, created_at, updated_at, deleted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, diaryId, user.getId(), requestedAt.atZone(SERVICE_ZONE).toLocalDate(),
                "관리자 생성 이력 테스트 일기", Timestamp.from(requestedAt), Timestamp.from(requestedAt), null);

        UUID generationId = UUID.randomUUID();
        boolean completed = status != GenerationStatus.PROCESSING;
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
                """, generationId, diaryId, generationPrompt.getId(), UUID.randomUUID(), "a".repeat(64),
                status.name(), status == GenerationStatus.SUCCEEDED ? "생성 이력 테스트 결과" : null,
                status == GenerationStatus.SUCCEEDED ? "generated/admin-history/" + generationId + ".png" : null,
                errorCode == null ? null : errorCode.name(), Timestamp.from(requestedAt),
                Timestamp.from(requestedAt), completed ? Timestamp.from(requestedAt.plusSeconds(1)) : null);
        return generationId;
    }

    private String bearerToken(User user) {
        return "Bearer " + accessTokenService.issue(user.getId(), Instant.now()).accessToken();
    }
}
