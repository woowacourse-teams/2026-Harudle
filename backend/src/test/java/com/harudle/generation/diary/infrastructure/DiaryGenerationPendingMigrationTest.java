package com.harudle.generation.diary.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class DiaryGenerationPendingMigrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRESQL_CONTAINER =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @BeforeEach
    void migratePreviousSchema() {
        flyway().clean();
        Flyway.configure()
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .dataSource(
                        POSTGRESQL_CONTAINER.getJdbcUrl(),
                        POSTGRESQL_CONTAINER.getUsername(),
                        POSTGRESQL_CONTAINER.getPassword()
                )
                .target(MigrationVersion.fromVersion("23"))
                .load()
                .migrate();
    }

    @Test
    @DisplayName("기존 DB를 변경하면 결과가 없는 대기 작업을 저장할 수 있다")
    void allowPendingGenerationAfterMigration() throws SQLException {
        SQLException exception = catchThrowableOfType(SQLException.class, () -> insertGeneration("PENDING"));
        assertThat(exception.getSQLState()).isEqualTo("23514");

        flyway().migrate();
        UUID generationId = insertGeneration("PENDING");

        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT status, title, image_object_key, error_code, completed_at
                        FROM diary_generations
                        WHERE id = ?
                        """)) {
            statement.setObject(1, generationId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("status")).isEqualTo("PENDING");
                assertThat(result.getString("title")).isNull();
                assertThat(result.getString("image_object_key")).isNull();
                assertThat(result.getString("error_code")).isNull();
                assertThat(result.getObject("completed_at")).isNull();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCESSING", "SUCCEEDED", "FAILED"})
    @DisplayName("DB를 변경해도 기존 생성 기록을 그대로 보존한다")
    void preserveExistingGeneration(String status) throws SQLException {
        UUID generationId = insertGeneration(status);
        String previousRecord = readGeneration(generationId);

        flyway().migrate();

        assertThat(readGeneration(generationId)).isEqualTo(previousRecord);
    }

    @Test
    @DisplayName("DB를 변경한 뒤에도 정해지지 않은 생성 상태는 저장할 수 없다")
    void rejectUnknownStatus() {
        flyway().migrate();

        SQLException exception = catchThrowableOfType(SQLException.class, () -> insertGeneration("UNKNOWN"));

        assertThat(exception.getSQLState()).isEqualTo("23514");
        assertThat(exception.getMessage()).contains("ck_diary_generations_status");
    }

    @Test
    @DisplayName("대기 작업을 생성 시각과 ID 순서로 찾을 인덱스를 만든다")
    void createPendingGenerationIndex() throws SQLException {
        flyway().migrate();

        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT index.indisvalid,
                               pg_get_indexdef(index.indexrelid) AS definition,
                               pg_get_expr(index.indpred, index.indrelid) AS predicate
                        FROM pg_index AS index
                        JOIN pg_class AS relation ON relation.oid = index.indexrelid
                        WHERE relation.relname = 'idx_diary_generations_pending_created_at_id'
                        """);
                ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getBoolean("indisvalid")).isTrue();
            assertThat(result.getString("definition")).contains("(created_at, id)");
            assertThat(result.getString("predicate")).isEqualTo("((status)::text = 'PENDING'::text)");
            assertThat(result.next()).isFalse();
        }
    }

    private UUID insertGeneration(String status) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID diaryId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO users (id, name) VALUES (?, '생성 상태 테스트')
                    """)) {
                statement.setObject(1, userId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO diaries (id, user_id, diary_date, source_text)
                    VALUES (?, ?, DATE '2026-10-20', '대기 상태 추가를 확인하는 일기')
                    """)) {
                statement.setObject(1, diaryId);
                statement.setObject(2, userId);
                statement.executeUpdate();
            }
            long promptId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO generation_prompts (
                        storyboard_prompt_text, image_style_prompt_text, image_asset_object_key
                    )
                    VALUES ('스토리보드 프롬프트', '이미지 스타일 프롬프트', 'references/style.png')
                    RETURNING id
                    """);
                    ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                promptId = result.getLong(1);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO diary_generations (
                        id, diary_id, prompt_id, idempotency_key, request_fingerprint, status, image_object_key
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setObject(1, generationId);
                statement.setObject(2, diaryId);
                statement.setLong(3, promptId);
                statement.setObject(4, UUID.randomUUID());
                statement.setString(5, "a".repeat(64));
                statement.setString(6, status);
                statement.setString(7, "SUCCEEDED".equals(status) ? "generated/" + generationId + ".png" : null);
                statement.executeUpdate();
            }
            connection.commit();
        }
        return generationId;
    }

    private String readGeneration(UUID generationId) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT row_to_json(generation)::text
                        FROM diary_generations AS generation
                        WHERE id = ?
                        """)) {
            statement.setObject(1, generationId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRESQL_CONTAINER.getJdbcUrl(),
                POSTGRESQL_CONTAINER.getUsername(),
                POSTGRESQL_CONTAINER.getPassword()
        );
    }

    private static Flyway flyway() {
        return Flyway.configure()
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .dataSource(
                        POSTGRESQL_CONTAINER.getJdbcUrl(),
                        POSTGRESQL_CONTAINER.getUsername(),
                        POSTGRESQL_CONTAINER.getPassword()
                )
                .cleanDisabled(false)
                .load();
    }
}
