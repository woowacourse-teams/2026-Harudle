package com.harudle.category.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.auth.application.AccessTokenService;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
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
}
