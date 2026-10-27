package com.harudle.feed.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class FeedNotificationSchemaMigrationTest {

    private static final UUID AUTHOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID READER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID LONG_NAME_USER = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final String LONG_NAME = "열글자를초과하는기존사용자이름";

    @Container
    private static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @BeforeEach
    void upgradeExistingSchema() throws SQLException {
        migrateExistingSchemaTo("18");
    }

    private void migrateExistingSchemaTo(String target) throws SQLException {
        flyway().clean();
        Flyway.configure()
                .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                .target("13")
                .load()
                .migrate();
        execute("INSERT INTO users (id, name) VALUES (?, ?)", AUTHOR, "중복이름");
        execute("INSERT INTO users (id, name) VALUES (?, ?)", READER, "중복이름");
        execute("INSERT INTO users (id, name) VALUES (?, ?)", LONG_NAME_USER, LONG_NAME);
        execute("INSERT INTO users (id, name) VALUES (?, ?)", GUEST, "체험사용자");
        execute("""
                INSERT INTO guest_sessions (id, guest_user_id, token_hash, expires_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '1 day')
                """, UUID.randomUUID(), GUEST, "a".repeat(64));
        flyway(target).migrate();
    }

    @Test
    @DisplayName("V13 업그레이드는 기존 이름을 보존하고 이미지 백필은 별도 작업으로 남긴다")
    void preserveLegacyProfiles() throws SQLException {
        assertThat(queryLong("SELECT count(*) FROM users WHERE name = ? AND nickname IS NULL", "중복이름"))
                .isEqualTo(2);
        assertThat(queryLong("SELECT count(*) FROM users WHERE id = ? AND name = ? AND nickname IS NULL",
                LONG_NAME_USER, LONG_NAME)).isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM users WHERE profile_image_code IS NULL"))
                .isEqualTo(4);
        assertThat(queryLong("SELECT count(*) FROM users WHERE id = ? AND profile_image_code IS NULL", GUEST))
                .isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM pg_tables WHERE schemaname = 'public' "
                + "AND tablename = 'nickname_reservations'")).isZero();
        flyway().validate();
        assertThat(flyway().migrate().migrationsExecuted).isZero();
    }

    @Test
    @DisplayName("빈 PostgreSQL에도 전체 마이그레이션과 카테고리 시드를 적용할 수 있다")
    void migrateEmptyDatabase() throws SQLException {
        flyway().clean();
        assertThat(flyway().migrate().migrationsExecuted).isEqualTo(18);
        flyway().validate();
        assertThat(queryLong("SELECT count(*) FROM categories WHERE is_active")).isEqualTo(2);
        assertThat(queryLong("""
                SELECT count(*) FROM pg_tables WHERE schemaname = 'public'
                AND tablename IN ('categories', 'feeds', 'comments', 'feed_likes',
                                  'notifications', 'push_registrations', 'notification_outbox')
                """)).isEqualTo(7);
    }

    @Test
    @DisplayName("설정한 닉네임은 활성 사용자 사이에서 고유하고 탈퇴하면 재사용할 수 있다")
    void enforceActiveNicknameUniqueness() throws SQLException {
        execute("UPDATE users SET nickname = ? WHERE id = ?", "하루들", AUTHOR);
        reject("23505", "UPDATE users SET nickname = ? WHERE id = ?", "하루들", READER);
        execute("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", AUTHOR);
        execute("UPDATE users SET nickname = ? WHERE id = ?", "하루들", READER);
        execute("UPDATE users SET nickname = ? WHERE id = ?", "HARUDLE", LONG_NAME_USER);
        execute("UPDATE users SET nickname = ? WHERE id = ?", "harudle", GUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " 이름", "이름\t", "열한글자닉네임은거절함", "\u1100\u1161"})
    @DisplayName("새 닉네임은 길이·앞뒤 공백·NFC 규칙을 만족해야 한다")
    void rejectInvalidNickname(String nickname) {
        reject("23514", "UPDATE users SET nickname = ? WHERE id = ?", nickname, AUTHOR);
    }

    @Test
    @DisplayName("카테고리를 보관해도 피드와 이름을 유지하고 활성 피드 삭제 후 재게시할 수 있다")
    void archiveCategoryAndRepublishDiary() throws SQLException {
        UUID diary = createDiary();
        UUID feed = createFeed(diary);
        reject("23505", "INSERT INTO feeds (id, diary_id, category_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), diary, categoryId());
        execute("UPDATE categories SET is_active = FALSE WHERE id = ?", categoryId());
        assertThat(queryLong("SELECT count(*) FROM feeds WHERE category_id = ?", categoryId())).isEqualTo(1);
        reject("23505", "INSERT INTO categories (name) VALUES (?)", "일상");
        reject("23001", "DELETE FROM categories WHERE id = ?", categoryId());
        execute("UPDATE feeds SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", feed);
        createFeed(diary);
        assertThat(queryLong("SELECT count(*) FROM feeds WHERE diary_id = ?", diary)).isEqualTo(2);
        assertThat(queryLong("SELECT count(*) FROM feeds WHERE diary_id = ? AND deleted_at IS NULL", diary))
                .isEqualTo(1);
        reject("23514", "UPDATE feeds SET like_count = -1 WHERE id = ?", feed);
        reject("23514", "UPDATE feeds SET comment_count = -1 WHERE id = ?", feed);
        reject("23514", "UPDATE categories SET sort_order = -1 WHERE id = ?", categoryId());
    }

    @Test
    @DisplayName("댓글 길이는 유니코드 코드 포인트 기준이고 공백 댓글을 거부한다")
    void validateCommentCodePoints() throws SQLException {
        UUID feed = createFeed(createDiary());
        String sql = "INSERT INTO comments (id, feed_id, author_id, content) VALUES (?, ?, ?, ?)";
        execute(sql, UUID.randomUUID(), feed, READER, "😀".repeat(100));
        reject("22001", sql, UUID.randomUUID(), feed, READER, "😀".repeat(101));
        reject("23514", sql, UUID.randomUUID(), feed, READER, "\t\n");
        reject("23514", sql, UUID.randomUUID(), feed, READER, " 댓글");
        reject("23502", sql, UUID.randomUUID(), feed, READER, null);
    }

    @Test
    @DisplayName("좋아요 취소는 알림을 보존하고 다시 누르면 새 행동의 알림을 만들 수 있다")
    void preserveNotificationsAfterUnlike() throws SQLException {
        UUID feed = createFeed(createDiary());
        UUID like = UUID.randomUUID();
        String likeSql = "INSERT INTO feed_likes (id, feed_id, user_id) VALUES (?, ?, ?)";
        String notificationSql = """
                INSERT INTO notifications (id, recipient_user_id, actor_user_id, feed_id, type, source_action_id)
                VALUES (?, ?, ?, ?, 'LIKE', ?)
                """;
        execute(likeSql, like, feed, READER);
        execute(notificationSql, UUID.randomUUID(), AUTHOR, READER, feed, like);
        reject("23505", likeSql, UUID.randomUUID(), feed, READER);
        reject("23505", notificationSql, UUID.randomUUID(), AUTHOR, READER, feed, like);
        execute("DELETE FROM feed_likes WHERE id = ?", like);
        assertThat(queryLong("SELECT count(*) FROM notifications WHERE source_action_id = ?", like)).isEqualTo(1);
        UUID newLike = UUID.randomUUID();
        execute(likeSql, newLike, feed, READER);
        execute(notificationSql, UUID.randomUUID(), AUTHOR, READER, feed, newLike);
        execute("DELETE FROM users WHERE id = ?", READER);
        assertThat(queryLong("SELECT count(*) FROM notifications WHERE feed_id = ? AND actor_user_id IS NULL", feed))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("푸시 등록의 계정·기기는 고정하고 폐기 후 새 등록으로 키를 재사용한다")
    void keepRegistrationOwnershipImmutable() throws SQLException {
        UUID device = UUID.randomUUID();
        UUID registration = createRegistration(AUTHOR, device, "token-original");
        reject("23505", """
                INSERT INTO push_registrations (id, user_id, device_id, recipient_value)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), READER, device, "token-other");
        reject("23505", """
                INSERT INTO push_registrations (id, user_id, device_id, recipient_value)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), READER, UUID.randomUUID(), "token-original");
        execute("UPDATE push_registrations SET recipient_value = ? WHERE id = ?", "token-refreshed", registration);
        reject("23514", "UPDATE push_registrations SET user_id = ? WHERE id = ?", READER, registration);
        reject("23514", "UPDATE push_registrations SET device_id = ? WHERE id = ?", UUID.randomUUID(), registration);
        execute("UPDATE push_registrations SET revoked_at = CURRENT_TIMESTAMP WHERE id = ?", registration);
        createRegistration(READER, device, "token-refreshed");
        reject("23514", "UPDATE push_registrations SET revoked_at = NULL WHERE id = ?", registration);
    }

    @Test
    @DisplayName("Outbox는 등록 소유자에게만 연결되고 같은 이벤트·기기의 중복 작업을 거부한다")
    void constrainOutboxRecipientAndDeduplication() throws SQLException {
        UUID feed = createFeed(createDiary());
        UUID registration = createRegistration(READER, UUID.randomUUID(), "token-one");
        UUID event = UUID.randomUUID();
        String sql = """
                INSERT INTO notification_outbox (id, event_id, feed_id, recipient_user_id, push_registration_id)
                VALUES (?, ?, ?, ?, ?)
                """;
        reject("23503", sql, UUID.randomUUID(), event, feed, AUTHOR, registration);
        execute(sql, UUID.randomUUID(), event, feed, READER, registration);
        reject("23505", sql, UUID.randomUUID(), event, feed, READER, registration);
        UUID otherDevice = createRegistration(READER, UUID.randomUUID(), "token-two");
        execute(sql, UUID.randomUUID(), event, feed, READER, otherDevice);
        assertThat(queryLong("SELECT count(*) FROM notification_outbox WHERE event_id = ?", event)).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING,false,false,false,true",
            "PROCESSING,true,true,false,true",
            "SENT,false,false,true,true",
            "CANCELLED,false,false,false,true",
            "FAILED,false,false,false,true",
            "PROCESSING,false,true,false,false",
            "PROCESSING,true,false,false,false",
            "PROCESSING,true,true,true,false",
            "PENDING,true,true,false,false",
            "PENDING,false,false,true,false",
            "SENT,false,false,false,false",
            "SENT,true,true,true,false",
            "CANCELLED,false,true,false,false",
            "FAILED,false,false,true,false"
    })
    @DisplayName("Outbox 상태에 맞는 임대 토큰·만료 시각·발송 시각만 허용한다")
    void validateOutboxStateFields(String status, boolean locked, boolean lease, boolean sent, boolean valid)
            throws SQLException {
        UUID feed = createFeed(createDiary());
        UUID registration = createRegistration(READER, UUID.randomUUID(), "token-state");
        Object[] values = {UUID.randomUUID(), UUID.randomUUID(), feed, READER, registration, status,
                locked ? UUID.randomUUID() : null,
                lease ? OffsetDateTime.now().plusMinutes(1) : null,
                sent ? OffsetDateTime.now() : null};
        String sql = """
                INSERT INTO notification_outbox
                    (id, event_id, feed_id, recipient_user_id, push_registration_id,
                     status, lock_token, locked_until, sent_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        if (valid) {
            execute(sql, values);
        } else {
            reject("23514", sql, values);
        }
    }

    @Test
    @DisplayName("V14는 기존 행 검사와 백필 없이 커밋하고 V18에서 CHECK 검증을 완료한다")
    void validateProfileConstraintsInSeparateMigration() throws SQLException {
        migrateExistingSchemaTo("14");
        assertThat(queryLong("""
                SELECT count(*) FROM pg_constraint
                WHERE conrelid = 'users'::regclass
                  AND conname IN ('ck_users_nickname', 'ck_users_profile_image_code')
                  AND NOT convalidated
                """)).isEqualTo(2);
        assertThat(queryLong("SELECT count(*) FROM users WHERE profile_image_code IS NULL")).isEqualTo(4);
        reject("23514", "UPDATE users SET nickname = ? WHERE id = ?", " 잘못된이름", AUTHOR);
        reject("23514", "UPDATE users SET profile_image_code = 6 WHERE id = ?", AUTHOR);

        flyway().migrate();

        assertThat(queryLong("""
                SELECT count(*) FROM pg_constraint
                WHERE conrelid = 'users'::regclass
                  AND conname IN ('ck_users_nickname', 'ck_users_profile_image_code')
                  AND convalidated
                """)).isEqualTo(2);
        assertThat(queryLong("""
                SELECT count(*) FROM pg_index
                WHERE indexrelid = 'uq_users_active_nickname'::regclass AND indisvalid
                """)).isEqualTo(1);
    }

    @Test
    @DisplayName("닉네임 인덱스 생성이 기존 쓰기를 기다리는 동안 사용자 조회·쓰기를 허용한다")
    void allowReadsAndWritesDuringConcurrentIndexBuild() throws Exception {
        migrateExistingSchemaTo("16");
        var executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = openConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("UPDATE users SET name = ? WHERE id = ?")) {
                statement.setString(1, "진행중트랜잭션");
                statement.setObject(2, AUTHOR);
                statement.executeUpdate();
            }
            var migration = executor.submit(() -> flyway("17").migrate());
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (queryLong("SELECT count(*) FROM pg_stat_progress_create_index "
                        + "WHERE relid = 'users'::regclass") == 0 && System.nanoTime() < deadline) {
                    if (migration.isDone()) {
                        migration.get(1, TimeUnit.SECONDS);
                        throw new AssertionError("인덱스 생성이 기존 트랜잭션을 기다리지 않았습니다");
                    }
                    Thread.sleep(25);
                }
                assertThat(queryLong("SELECT count(*) FROM pg_stat_progress_create_index "
                        + "WHERE relid = 'users'::regclass")).isEqualTo(1);
                try (Connection probe = openConnection(); var statement = probe.createStatement()) {
                    statement.setQueryTimeout(5);
                    statement.execute("SET lock_timeout = '1s'");
                    try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM users")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).isEqualTo(4);
                    }
                    assertThat(statement.executeUpdate("UPDATE users SET nickname = '인덱스생성중' "
                            + "WHERE id = '" + READER + "'")).isEqualTo(1);
                }
                blocker.rollback();
                assertThat(migration.get(20, TimeUnit.SECONDS).migrationsExecuted).isEqualTo(1);
            } finally {
                blocker.rollback();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("이미지 백필은 회원만 처리하고 기존 설정·탈퇴·체험 사용자를 보존하며 재실행할 수 있다")
    void backfillProfilesWithoutOverwritingAssignedImages() throws Exception {
        UUID firstUser = UUID.fromString("00000000-0000-0000-0000-000000000000");
        execute("INSERT INTO users (id, name) VALUES (?, ?)", firstUser, "첫UUID회원");
        UUID deletedUser = UUID.fromString("00000000-0000-0000-0000-000000000005");
        execute("INSERT INTO users (id, name, deleted_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                deletedUser, "탈퇴회원");
        execute("UPDATE users SET profile_image_code = 5 WHERE id = ?", AUTHOR);
        try (Connection connection = openConnection()) {
            loadProfileBackfill(connection);
            callProfileBackfill(connection, 1);
            assertThat(queryLong("SELECT profile_image_code FROM users WHERE id = ?", firstUser)).isEqualTo(1);
            assertThat(queryLong("SELECT profile_image_code FROM users WHERE id = ?", AUTHOR)).isEqualTo(5);
            assertThat(queryLong("SELECT profile_image_code FROM users WHERE id = ?", READER)).isEqualTo(3);
            assertThat(queryLong("SELECT profile_image_code FROM users WHERE id = ?", LONG_NAME_USER)).isEqualTo(4);
            assertThat(queryLong("SELECT count(*) FROM users WHERE id IN (?, ?) "
                    + "AND profile_image_code IS NULL", GUEST, deletedUser)).isEqualTo(2);

            execute("UPDATE users SET profile_image_code = 1 WHERE id = ?", READER);
            callProfileBackfill(connection, 1);
            assertThat(queryLong("SELECT profile_image_code FROM users WHERE id = ?", READER)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("백필 중간 실패는 앞선 배치의 커밋을 보존하고 재실행하면 남은 회원을 처리한다")
    void resumeBackfillAfterCommittedBatchAndFailure() throws Exception {
        execute("""
                ALTER TABLE users ADD CONSTRAINT test_backfill_failure
                CHECK (id <> '00000000-0000-0000-0000-000000000002' OR profile_image_code IS NULL)
                """);
        try (Connection connection = openConnection()) {
            loadProfileBackfill(connection);
            SQLException failure = catchThrowableOfType(SQLException.class, () -> callProfileBackfill(connection, 1));
            assertThat((Throwable) failure).isNotNull();
            assertThat(failure.getSQLState()).isEqualTo("23514");
            assertThat(queryLong("SELECT profile_image_code FROM users WHERE id = ?", AUTHOR)).isEqualTo(2);
            assertThat(queryLong("SELECT count(*) FROM users WHERE id IN (?, ?) "
                    + "AND profile_image_code IS NULL", READER, LONG_NAME_USER)).isEqualTo(2);

            execute("ALTER TABLE users DROP CONSTRAINT test_backfill_failure");
            callProfileBackfill(connection, 1);
            assertThat(queryLong("SELECT profile_image_code FROM users WHERE id = ?", AUTHOR)).isEqualTo(2);
            assertThat(queryLong("SELECT profile_image_code FROM users WHERE id = ?", READER)).isEqualTo(3);
            assertThat(queryLong("SELECT profile_image_code FROM users WHERE id = ?", LONG_NAME_USER)).isEqualTo(4);
            assertThat(queryLong("SELECT count(*) FROM users WHERE id = ? AND profile_image_code IS NULL", GUEST))
                    .isEqualTo(1);
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1, 10001})
    @DisplayName("백필은 NULL 또는 범위를 벗어난 배치 크기를 거절한다")
    void rejectInvalidBackfillBatchSize(Integer batchSize) throws Exception {
        try (Connection connection = openConnection()) {
            loadProfileBackfill(connection);
            SQLException failure = catchThrowableOfType(SQLException.class,
                    () -> callProfileBackfill(connection, batchSize));
            assertThat((Throwable) failure).isNotNull();
            assertThat(failure.getSQLState()).isEqualTo("22023");
            assertThat(queryLong("SELECT count(*) FROM users WHERE profile_image_code IS NULL")).isEqualTo(4);
        }
    }

    private static void loadProfileBackfill(Connection connection) throws IOException, SQLException {
        try (var resource = FeedNotificationSchemaMigrationTest.class
                .getResourceAsStream("/db/maintenance/backfill_user_profile_images.sql")) {
            assertThat(resource).isNotNull();
            try (var statement = connection.createStatement()) {
                statement.execute("SET plan_cache_mode = force_generic_plan");
                statement.execute(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    private static void callProfileBackfill(Connection connection, Integer batchSize) throws SQLException {
        try (var statement = connection.prepareStatement("CALL pg_temp.backfill_user_profile_images(?)")) {
            statement.setObject(1, batchSize, Types.INTEGER);
            statement.execute();
        }
    }

    private UUID createDiary() throws SQLException {
        UUID diary = UUID.randomUUID();
        execute("""
                INSERT INTO diaries (id, user_id, diary_date, source_text)
                VALUES (?, ?, CURRENT_DATE, '피드 마이그레이션 검증')
                """, diary, AUTHOR);
        return diary;
    }

    private UUID createFeed(UUID diary) throws SQLException {
        UUID feed = UUID.randomUUID();
        execute("INSERT INTO feeds (id, diary_id, category_id) VALUES (?, ?, ?)", feed, diary, categoryId());
        return feed;
    }

    private UUID createRegistration(UUID user, UUID device, String token) throws SQLException {
        UUID registration = UUID.randomUUID();
        execute("""
                INSERT INTO push_registrations (id, user_id, device_id, recipient_value)
                VALUES (?, ?, ?, ?)
                """, registration, user, device, token);
        return registration;
    }

    private long categoryId() throws SQLException {
        return queryLong("SELECT id FROM categories WHERE name = ?", "일상");
    }

    private void reject(String sqlState, String sql, Object... values) {
        SQLException exception = catchThrowableOfType(SQLException.class, () -> execute(sql, values));
        assertThat((Throwable) exception).isNotNull();
        assertThat(exception.getSQLState()).isEqualTo(sqlState);
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    private long queryLong(String sql, Object... values) throws SQLException {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private static void bind(PreparedStatement statement, Object[] values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            statement.setObject(i + 1, values[i]);
        }
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
    }

    private static Flyway flyway() {
        return flyway("18");
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .group(false)
                .target(target)
                .cleanDisabled(false)
                .load();
    }
}
