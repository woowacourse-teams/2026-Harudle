package com.harudle.generation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class DiaryGenerationImageObjectKeyMigrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final MigrationVersion INITIAL_SCHEMA = MigrationVersion.fromVersion("1");
    private static final UUID USER_ID = UUID.fromString("96c7cb9d-c451-4a98-a28b-eecf56c47482");
    private static final UUID DIARY_ID = UUID.fromString("83e48828-096d-40cc-8458-c66b82b24476");
    private static final UUID GENERATION_ID = UUID.fromString("b96446bb-c35b-4662-907c-4a3487b16837");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("cb6c1a47-4d64-4dd3-96db-63625c53ace4");
    private static final String CHECK_VIOLATION_SQL_STATE = "23514";

    @Container
    private static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @BeforeEach
    void migrateInitialSchema() {
        flyway().clean();
        Flyway.configure()
                .dataSource(
                        POSTGRESQL_CONTAINER.getJdbcUrl(),
                        POSTGRESQL_CONTAINER.getUsername(),
                        POSTGRESQL_CONTAINER.getPassword()
                )
                .target(INITIAL_SCHEMA)
                .load()
                .migrate();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    @DisplayName("이미지 키가 null이거나 공백인 기존 성공 행을 저장소 실패로 보정한다")
    void migrateSucceededGenerationWithBlankImageObjectKey(String imageObjectKey) throws SQLException {
        insertGeneration("comic_generations", "SUCCEEDED", imageObjectKey);

        flyway().migrate();

        try (
                Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT
                            generation.status,
                            generation.image_object_key,
                            generation.error_code,
                            generation.completed_at,
                            diary.deleted_at = generation.completed_at AS discarded_at_completion
                        FROM diary_generations AS generation
                        JOIN diaries AS diary ON diary.id = generation.diary_id
                        WHERE generation.id = ?
                        """)
        ) {
            statement.setObject(1, GENERATION_ID);

            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("status")).isEqualTo("FAILED");
                assertThat(result.getString("image_object_key")).isNull();
                assertThat(result.getString("error_code")).isEqualTo("IMAGE_STORAGE_ERROR");
                assertThat(result.getObject("completed_at")).isNotNull();
                assertThat(result.getBoolean("discarded_at_completion")).isTrue();
                assertThat(result.next()).isFalse();
            }
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    @DisplayName("이미지 키가 null이거나 공백인 성공 행 저장을 거부한다")
    void rejectSucceededGenerationWithBlankImageObjectKey(String imageObjectKey) {
        flyway().migrate();

        SQLException exception = catchThrowableOfType(
                SQLException.class,
                () -> insertGeneration("diary_generations", "SUCCEEDED", imageObjectKey)
        );

        assertThat(exception.getSQLState()).isEqualTo(CHECK_VIOLATION_SQL_STATE);
        assertThat(exception.getMessage()).contains("ck_diary_generations_succeeded_image_key");
    }

    @Test
    @DisplayName("처리 중인 생성 행은 이미지 키 없이 저장할 수 있다")
    void allowProcessingGenerationWithoutImageObjectKey() throws SQLException {
        flyway().migrate();

        insertGeneration("diary_generations", "PROCESSING", null);

        try (
                Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT COUNT(*)
                        FROM diary_generations
                        WHERE id = ?
                        """)
        ) {
            statement.setObject(1, GENERATION_ID);

            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }

    private void insertGeneration(String tableName, String status, String imageObjectKey) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            insertUser(connection);
            insertDiary(connection);
            long promptId = insertPrompt(connection);

            String sql = """
                    INSERT INTO %s (
                        id,
                        diary_id,
                        prompt_id,
                        idempotency_key,
                        request_fingerprint,
                        status,
                        image_object_key
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.formatted(tableName);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, GENERATION_ID);
                statement.setObject(2, DIARY_ID);
                statement.setLong(3, promptId);
                statement.setObject(4, IDEMPOTENCY_KEY);
                statement.setString(5, "a".repeat(64));
                statement.setString(6, status);
                if (imageObjectKey == null) {
                    statement.setNull(7, Types.VARCHAR);
                } else {
                    statement.setString(7, imageObjectKey);
                }
                statement.executeUpdate();
            }

            connection.commit();
        }
    }

    private static void insertUser(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO users (id, primary_email, name)
                VALUES (?, ?, ?)
                """)) {
            statement.setObject(1, USER_ID);
            statement.setString(2, "migration-test@harudle.example");
            statement.setString(3, "하루들");
            statement.executeUpdate();
        }
    }

    private static void insertDiary(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO diaries (id, user_id, diary_date, source_text)
                VALUES (?, ?, DATE '2026-08-12', ?)
                """)) {
            statement.setObject(1, DIARY_ID);
            statement.setObject(2, USER_ID);
            statement.setString(3, "마이그레이션 테스트 일기");
            statement.executeUpdate();
        }
    }

    private static long insertPrompt(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO generation_prompts (
                    storyboard_prompt_text,
                    image_style_prompt_text,
                    image_asset_object_key
                )
                VALUES (?, ?, ?)
                RETURNING id
                """)) {
            statement.setString(1, "스토리보드 프롬프트");
            statement.setString(2, "이미지 스타일 프롬프트");
            statement.setString(3, "references/style.png");

            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
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
                .dataSource(
                        POSTGRESQL_CONTAINER.getJdbcUrl(),
                        POSTGRESQL_CONTAINER.getUsername(),
                        POSTGRESQL_CONTAINER.getPassword()
                )
                .cleanDisabled(false)
                .load();
    }
}
