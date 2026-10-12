package com.harudle.category.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.harudle.auth.application.AccessTokenService;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.category.service.port.CategoryReader;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import io.restassured.http.ContentType;
import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CategoryControllerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private CategoryReader categoryReader;

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.mockMvc(mockMvc);
        jdbc.update("UPDATE categories SET sort_order = 2 WHERE name = '일상'");
        jdbc.update("UPDATE categories SET is_active = FALSE, sort_order = 0 WHERE name = '우테코'");
        jdbc.update("INSERT INTO categories (name, sort_order) VALUES ('여행', 0), ('취미', 0)");
    }

    @AfterEach
    void tearDown() {
        RestAssuredMockMvc.reset();
    }

    @Test
    @DisplayName("비로그인 사용자는 활성 목록을 순서와 ID 오름차순으로 조회한다")
    void readsPublicCategories() {
        MockMvcResponse response = RestAssuredMockMvc.given().get("/api/v1/categories");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.contentType()).startsWith("application/json");
        assertThat((Object) response.jsonPath().get("[0].id")).isInstanceOf(Number.class);
        assertThat(response.jsonPath().getList("name", String.class)).containsExactly("여행", "취미", "일상");
        assertThat(response.jsonPath().getList("sortOrder", Integer.class)).containsExactly(0, 0, 2);
        assertThat(response.jsonPath().getList("active", Boolean.class)).containsOnly(true);
        assertThat(response.jsonPath().getLong("[0].id")).isLessThan(response.jsonPath().getLong("[1].id"));
    }

    @Test
    @DisplayName("관리자는 비활성 항목까지 포함한 전체 목록을 정렬해서 조회한다")
    void readsAdminCategories() {
        User admin = userRepository.saveAndFlush(new User(null, "관리자", Instant.now()));
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", admin.getId());
        entityManager.clear();

        MockMvcResponse response = RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, bearerToken(admin))
                .get("/api/v1/admin/categories");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getList("name", String.class))
                .containsExactly("우테코", "여행", "취미", "일상");
        assertThat(response.jsonPath().getList("sortOrder", Integer.class)).containsExactly(0, 0, 0, 2);
        assertThat(response.jsonPath().getList("active", Boolean.class)).containsExactly(false, true, true, true);
    }

    @Test
    @DisplayName("비로그인 사용자와 일반 회원은 관리자 목록을 조회할 수 없다")
    void rejectsAdminAccess() {
        MockMvcResponse anonymous = RestAssuredMockMvc.given().get("/api/v1/admin/categories");
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.contentType()).startsWith("application/problem+json");
        assertThat(anonymous.jsonPath().getString("code")).isEqualTo("UNAUTHORIZED");

        User member = userRepository.saveAndFlush(new User(null, "회원", Instant.now()));
        MockMvcResponse response = RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, bearerToken(member))
                .get("/api/v1/admin/categories");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.jsonPath().getString("code")).isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("활성 카테고리가 없으면 빈 목록을 반환한다")
    void returnsEmptyPublicCategories() {
        jdbc.update("UPDATE categories SET is_active = FALSE");

        MockMvcResponse response = RestAssuredMockMvc.given().get("/api/v1/categories");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getList("$")).isEmpty();
    }

    private String bearerToken(User user) {
        return "Bearer " + accessTokenService.issue(user.getId(), Instant.now()).accessToken();
    }

    @Test
    @DisplayName("관리자는 정규화한 이름으로 활성 카테고리를 만들고 순서를 생략하면 마지막에 추가한다")
    void createsCategory() {
        User admin = saveAdmin();
        MockMvcResponse response = createRequest(admin)
                .body("{\"name\":\"  운동  \",\"sortOrder\":4}")
                .post("/api/v1/admin/categories");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.jsonPath().getString("name")).isEqualTo("운동");
        assertThat(response.jsonPath().getInt("sortOrder")).isEqualTo(4);
        assertThat(response.jsonPath().getBoolean("active")).isTrue();

        jdbc.update("UPDATE categories SET sort_order = 7 WHERE name = '우테코'");
        MockMvcResponse appended = createRequest(admin)
                .body("{\"name\":\"독서\"}")
                .post("/api/v1/admin/categories");
        assertThat(appended.statusCode()).isEqualTo(201);
        assertThat(appended.jsonPath().getInt("sortOrder")).isEqualTo(8);
        MockMvcResponse list = RestAssuredMockMvc.given().get("/api/v1/categories");
        assertThat(list.jsonPath().getList("name", String.class))
                .containsExactly("여행", "취미", "일상", "운동", "독서");
    }

    @Test
    @DisplayName("비활성 카테고리와 정규화한 이름이 중복되면 409를 반환한다")
    void rejectsDuplicateCategoryName() {
        MockMvcResponse response = createRequest(saveAdmin())
                .body("{\"name\":\" 우테코 \"}")
                .post("/api/v1/admin/categories");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.jsonPath().getString("code")).isEqualTo("CATEGORY_NAME_ALREADY_USED");
    }

    @Test
    @DisplayName("잘못된 이름과 음수 및 정수가 아닌 순서는 400을 반환한다")
    void rejectsInvalidCategory() {
        User admin = saveAdmin();
        for (String body : new String[]{
                "{\"name\":\" \"}",
                "{\"name\":\"" + "가".repeat(21) + "\"}",
                "{\"name\":\"운동\",\"sortOrder\":-1}",
                "{\"name\":\"운동\",\"sortOrder\":1.5}",
                "{\"name\":\"운동\",\"sortOrder\":2147483648}"
        }) {
            MockMvcResponse response = createRequest(admin).body(body).post("/api/v1/admin/categories");
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
        }
    }

    @Test
    @DisplayName("카테고리 생성은 관리자만 가능하며 Bearer 인증에 CSRF 토큰을 요구하지 않는다")
    void protectsCategoryCreation() {
        User member = userRepository.saveAndFlush(new User(null, "회원", Instant.now()));
        MockMvcResponse forbidden = createRequest(member)
                .body("{\"name\":\"운동\"}")
                .post("/api/v1/admin/categories");
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(forbidden.jsonPath().getString("code")).isEqualTo("FORBIDDEN");

        MockMvcResponse anonymous = RestAssuredMockMvc.given()
                .postProcessors(csrf())
                .contentType(ContentType.JSON)
                .body("{\"name\":\"운동\"}")
                .post("/api/v1/admin/categories");
        assertThat(anonymous.statusCode()).isEqualTo(401);

        MockMvcResponse missingCsrf = RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, bearerToken(saveAdmin()))
                .contentType(ContentType.JSON)
                .body("{\"name\":\"운동\"}")
                .post("/api/v1/admin/categories");
        assertThat(missingCsrf.statusCode()).isEqualTo(201);
    }

    private User saveAdmin() {
        User admin = userRepository.saveAndFlush(new User(null, "관리자", Instant.now()));
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", admin.getId());
        entityManager.clear();
        return admin;
    }

    private MockMvcRequestSpecification createRequest(User user) {
        return RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                .contentType(ContentType.JSON);
    }

    @Test
    @DisplayName("보낸 항목만 수정하고 비활성 항목은 기존 표시를 유지하며 다시 활성화할 수 있다")
    void updatesCategoryPartially() {
        User admin = saveAdmin();
        long id = categoryId("일상");
        MockMvcResponse renamed = createRequest(admin)
                .body("{\"name\":\" 일상 생활 \"}")
                .patch("/api/v1/admin/categories/{categoryId}", id);
        assertThat(renamed.statusCode()).isEqualTo(200);
        assertThat(renamed.jsonPath().getString("name")).isEqualTo("일상 생활");
        assertThat(renamed.jsonPath().getInt("sortOrder")).isEqualTo(2);
        assertThat(renamed.jsonPath().getBoolean("active")).isTrue();

        MockMvcResponse inactive = createRequest(admin)
                .body("{\"sortOrder\":5,\"active\":false}")
                .patch("/api/v1/admin/categories/{categoryId}", id);
        assertThat(inactive.statusCode()).isEqualTo(200);
        assertThat(inactive.jsonPath().getString("name")).isEqualTo("일상 생활");
        assertThat(inactive.jsonPath().getInt("sortOrder")).isEqualTo(5);
        assertThat(inactive.jsonPath().getBoolean("active")).isFalse();
        assertThat(categoryReader.get(id).name()).isEqualTo("일상 생활");
        assertThat(categoryReader.get(id).active()).isFalse();
        assertThat(RestAssuredMockMvc.given().get("/api/v1/categories").jsonPath().getList("name"))
                .doesNotContain("일상 생활");

        MockMvcResponse reactivated = createRequest(admin).body("{\"active\":true}")
                .patch("/api/v1/admin/categories/{categoryId}", id);
        assertThat(reactivated.statusCode()).isEqualTo(200);
        assertThat(reactivated.jsonPath().getBoolean("active")).isTrue();
    }

    @Test
    @DisplayName("빈 수정과 null 및 잘못된 값은 400이고 없는 카테고리는 404다")
    void rejectsInvalidCategoryUpdate() {
        User admin = saveAdmin();
        long id = categoryId("일상");
        for (String body : new String[]{
                "{}", "{\"name\":null}", "{\"sortOrder\":null}", "{\"active\":null}",
                "{\"name\":\" \"}", "{\"name\":\"" + "가".repeat(21) + "\"}",
                "{\"sortOrder\":-1}", "{\"sortOrder\":1.5}"
        }) {
            MockMvcResponse response = createRequest(admin).body(body)
                    .patch("/api/v1/admin/categories/{categoryId}", id);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
        }
        MockMvcResponse missing = createRequest(admin).body("{\"active\":false}")
                .patch("/api/v1/admin/categories/{categoryId}", Long.MAX_VALUE);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.jsonPath().getString("code")).isEqualTo("CATEGORY_NOT_FOUND");
    }

    @Test
    @DisplayName("수정한 이름이 비활성 카테고리와 중복되면 409다")
    void rejectsDuplicateCategoryUpdate() {
        MockMvcResponse response = createRequest(saveAdmin()).body("{\"name\":\" 우테코 \"}")
                .patch("/api/v1/admin/categories/{categoryId}", categoryId("일상"));
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.jsonPath().getString("code")).isEqualTo("CATEGORY_NAME_ALREADY_USED");
    }

    @Test
    @DisplayName("일반 회원은 카테고리를 수정할 수 없다")
    void rejectsMemberCategoryUpdate() {
        User member = userRepository.saveAndFlush(new User(null, "회원", Instant.now()));
        MockMvcResponse response = createRequest(member).body("{\"active\":false}")
                .patch("/api/v1/admin/categories/{categoryId}", categoryId("일상"));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.jsonPath().getString("code")).isEqualTo("FORBIDDEN");
    }

    private long categoryId(String name) {
        return jdbc.queryForObject("SELECT id FROM categories WHERE name = ?", Long.class, name);
    }
}
