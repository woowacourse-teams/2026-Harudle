package com.harudle.auth.infrastructure.nickname;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PublicProfileBackfillTest {

    @Container
    static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .target("21")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc.update("""
                INSERT INTO nickname_candidates (nickname, allocation_order)
                VALUES ('신나는 수달', 1), ('행복한 고양이', 2), ('졸린 토끼', 3), ('배고픈 판다', 4)
                """);
    }

    @Test
    @DisplayName("백필은 빈 값만 배정하고 OAuth 이름·기존 닉네임·이미지를 보존하며 재실행할 수 있다")
    void assignsMissingValuesAndPreservesExistingProfiles() throws Exception {
        insertUser(0, null, null);
        insertUser(1, null, 5);
        insertUser(2, "기존닉네임", null);
        insertUser(3, "신나는 수달", 2);
        var completed = profile(3);

        try (var connection = openBackfillConnection()) {
            call(connection, 1);

            assertThat(profile(0).get("nickname")).isEqualTo("행복한 고양이");
            assertThat(profile(1).get("nickname")).isEqualTo("졸린 토끼");
            assertThat(jdbc.queryForObject("SELECT profile_image_code FROM users WHERE id = ?", Short.class, id(1)))
                    .isEqualTo((short) 5);
            assertThat(profile(2).get("nickname")).isEqualTo("기존닉네임");
            assertThat(profile(3)).isEqualTo(completed);
            assertThat(number("SELECT count(*) FROM users WHERE name = 'OAuth원래이름'")).isEqualTo(4);
            assertThat(number("SELECT count(*) FROM users WHERE profile_image_code BETWEEN 1 AND 5"))
                    .isEqualTo(4);
            assertThat(number("SELECT count(*) FROM users WHERE updated_at > '2000-01-01'")).isEqualTo(3);
            assertThat(number("SELECT count(*) FROM nickname_candidates WHERE assigned_user_id IS NOT NULL"))
                    .isEqualTo(2);
            assertOwnershipIsConsistent();

            var before = jdbc.queryForList("SELECT * FROM users ORDER BY id");
            var candidates = jdbc.queryForList("SELECT * FROM nickname_candidates ORDER BY allocation_order");
            call(connection, 100);
            assertThat(jdbc.queryForList("SELECT * FROM users ORDER BY id")).isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT * FROM nickname_candidates ORDER BY allocation_order"))
                    .isEqualTo(candidates);
        }
    }

    @Test
    @DisplayName("탈퇴 회원과 체험 회원은 만료된 체험 세션을 포함해 백필에서 제외한다")
    void excludesDeletedAndGuestUsers() throws Exception {
        insertUser(1, null, null);
        insertUser(2, null, null);
        insertUser(3, null, null);
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", id(2));
        jdbc.update("""
                INSERT INTO guest_sessions (id, guest_user_id, token_hash, created_at, expires_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '1 day')
                """, UUID.randomUUID(), id(3), "a".repeat(64));
        var deleted = profile(2);
        var guest = profile(3);

        try (var connection = openBackfillConnection()) {
            call(connection, 100);
        }

        assertThat(profile(1).get("nickname")).isEqualTo("신나는 수달");
        assertThat(profile(2)).isEqualTo(deleted);
        assertThat(profile(3)).isEqualTo(guest);
        assertOwnershipIsConsistent();
    }

    @Test
    @DisplayName("배치 실패는 회원과 후보 점유를 함께 롤백하고 이전 배치는 보존한다")
    void rollsBackCurrentBatchAndResumesAfterFailure() throws Exception {
        for (int i = 1; i <= 4; i++) {
            insertUser(i, null, null);
        }
        jdbc.execute("""
                ALTER TABLE nickname_candidates ADD CONSTRAINT test_backfill_failure
                CHECK (assigned_user_id IS DISTINCT FROM '00000000-0000-0000-0000-000000000004'::UUID)
                """);

        try (var connection = openBackfillConnection()) {
            assertFailure(connection, 2, "23514");
            var firstBatch = jdbc.queryForList("SELECT * FROM users WHERE id IN (?, ?) ORDER BY id", id(1), id(2));
            assertThat(number("SELECT count(*) FROM users WHERE nickname IS NOT NULL")).isEqualTo(2);
            assertThat(number("SELECT count(*) FROM nickname_candidates WHERE assigned_user_id IS NOT NULL"))
                    .isEqualTo(2);
            assertThat(profile(3).get("profile_image_code")).isNull();
            assertOwnershipIsConsistent();

            jdbc.execute("ALTER TABLE nickname_candidates DROP CONSTRAINT test_backfill_failure");
            call(connection, 2);

            assertThat(jdbc.queryForList("SELECT * FROM users WHERE id IN (?, ?) ORDER BY id", id(1), id(2)))
                    .isEqualTo(firstBatch);
            assertThat(number("SELECT count(*) FROM users WHERE nickname IS NOT NULL")).isEqualTo(4);
            assertOwnershipIsConsistent();
        }
    }

    @Test
    @DisplayName("후보 부족 시 현재 배치만 롤백하고 후보 추가 후 재실행할 수 있다")
    void reportsExhaustionWithoutPartialOwnership() throws Exception {
        insertUser(1, null, null);
        insertUser(2, null, null);
        jdbc.update("DELETE FROM nickname_candidates WHERE allocation_order > 1");

        try (var connection = openBackfillConnection()) {
            assertFailure(connection, 2, "P0001");
            assertThat(number("SELECT count(*) FROM users WHERE nickname IS NOT NULL")).isZero();
            assertThat(number("SELECT count(*) FROM nickname_candidates WHERE assigned_user_id IS NOT NULL"))
                    .isZero();
            assertOwnershipIsConsistent();

            jdbc.update("INSERT INTO nickname_candidates (nickname, allocation_order) VALUES ('행복한 고양이', 2)");
            call(connection, 2);
            assertThat(number("SELECT count(*) FROM users WHERE nickname IS NOT NULL")).isEqualTo(2);
            assertOwnershipIsConsistent();
        }
    }

    @Test
    @DisplayName("다른 가입 트랜잭션이 잠근 후보는 건너뛰고 서로 다른 닉네임을 배정한다")
    void skipsCandidateLockedByConcurrentSignup() throws Exception {
        insertUser(1, null, null);

        try (var signup = dataSource.getConnection(); var backfill = openBackfillConnection()) {
            signup.setAutoCommit(false);
            var signupJdbc = new JdbcTemplate(new SingleConnectionDataSource(signup, true));
            signupJdbc.queryForList("SELECT nickname FROM nickname_candidates WHERE allocation_order = 1 FOR UPDATE");
            signupJdbc.update("""
                    INSERT INTO users (id, name, nickname, profile_image_code)
                    VALUES (?, '신규가입', '신나는 수달', 3)
                    """, id(2));
            signupJdbc.update("UPDATE nickname_candidates SET assigned_user_id = ? WHERE allocation_order = 1", id(2));

            call(backfill, 100);
            signup.commit();
        }

        assertThat(profile(1).get("nickname")).isEqualTo("행복한 고양이");
        assertThat(profile(2).get("nickname")).isEqualTo("신나는 수달");
        assertOwnershipIsConsistent();
    }

    private Connection openBackfillConnection() throws Exception {
        var connection = dataSource.getConnection();
        try (var resource = getClass().getResourceAsStream("/db/maintenance/backfill_user_public_profiles.sql");
             var statement = connection.createStatement()) {
            assertThat(resource).isNotNull();
            statement.execute("SET lock_timeout = '2s'");
            statement.execute("SET statement_timeout = '10s'");
            statement.execute("SET plan_cache_mode = force_generic_plan");
            statement.execute(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception failure) {
            connection.close();
            throw failure;
        }
        return connection;
    }

    private void call(Connection connection, int batchSize) throws SQLException {
        try (var statement = connection.prepareStatement("CALL pg_temp.backfill_user_public_profiles(?)")) {
            statement.setInt(1, batchSize);
            statement.execute();
        }
    }

    private void assertFailure(Connection connection, int batchSize, String sqlState) {
        var failure = catchThrowableOfType(SQLException.class, () -> call(connection, batchSize));
        assertThat((Throwable) failure).isNotNull();
        assertThat(failure.getSQLState()).isEqualTo(sqlState);
    }

    private void insertUser(long value, String nickname, Integer imageCode) {
        jdbc.update("""
                INSERT INTO users (id, name, nickname, profile_image_code, updated_at)
                VALUES (?, 'OAuth원래이름', ?, ?, '2000-01-01')
                """, id(value), nickname, imageCode);
    }

    private UUID id(long value) {
        return new UUID(0, value);
    }

    private Map<String, Object> profile(long value) {
        return jdbc.queryForMap("SELECT * FROM users WHERE id = ?", id(value));
    }

    private long number(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private void assertOwnershipIsConsistent() {
        assertThat(number("""
                SELECT count(*) FROM nickname_candidates c
                JOIN users u ON u.id = c.assigned_user_id
                WHERE u.nickname IS DISTINCT FROM c.nickname OR u.deleted_at IS NOT NULL
                """)).isZero();
    }
}
