package com.harudle.auth.infrastructure.nickname;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.auth.domain.policy.NicknamePolicy;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class NicknameCandidateMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        flyway("21").clean();
        flyway("21").migrate();
    }

    @Test
    @DisplayName("V22는 유효한 100 × 100 후보를 등록하고 이후 Flyway 실행에서는 재등록하지 않는다")
    void seedsTenThousandValidCandidatesOnce() {
        assertThat(flyway("22").migrate().migrationsExecuted).isEqualTo(1);
        var nicknames = jdbc.queryForList("SELECT nickname FROM nickname_candidates", String.class);

        assertThat(nicknames).hasSize(10_000).doesNotHaveDuplicates();
        assertThat(nicknames.stream().map(name -> name.split(" ")[0]).distinct().count()).isEqualTo(100);
        assertThat(nicknames.stream().map(name -> name.split(" ")[1]).distinct().count()).isEqualTo(100);
        nicknames.forEach(name -> assertThat(NicknamePolicy.normalizeAndValidate(name)).isEqualTo(name));
        assertThat(number("SELECT COUNT(DISTINCT allocation_order) FROM nickname_candidates")).isEqualTo(10_000);
        assertThat(number("SELECT MIN(allocation_order) FROM nickname_candidates")).isEqualTo(1);
        assertThat(number("SELECT MAX(allocation_order) FROM nickname_candidates")).isEqualTo(10_000);
        var before = jdbc.queryForList("SELECT * FROM nickname_candidates ORDER BY allocation_order");

        assertThat(flyway("22").migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM nickname_candidates ORDER BY allocation_order")).isEqualTo(before);
    }

    @Test
    @DisplayName("등록 SQL을 재실행해도 기존 순서·소유자를 보존하고 누락된 후보만 뒤에 추가한다")
    void addsMissingCandidatesWithoutOverwritingExistingRows() {
        UUID owner = UUID.randomUUID();
        insertUser(owner, "신나는 수달", false);
        jdbc.update("""
                INSERT INTO nickname_candidates (nickname, allocation_order, assigned_user_id)
                VALUES ('신나는 수달', 50, ?)
                """, owner);
        seed();

        assertThat(number("SELECT COUNT(*) FROM nickname_candidates")).isEqualTo(10_000);
        assertThat(number("SELECT allocation_order FROM nickname_candidates WHERE nickname = '신나는 수달'"))
                .isEqualTo(50);
        assertThat(jdbc.queryForObject("""
                SELECT assigned_user_id FROM nickname_candidates WHERE nickname = '신나는 수달'
                """, UUID.class)).isEqualTo(owner);
        var before = jdbc.queryForList("SELECT * FROM nickname_candidates ORDER BY allocation_order");
        seed();
        assertThat(jdbc.queryForList("SELECT * FROM nickname_candidates ORDER BY allocation_order")).isEqualTo(before);

        jdbc.update("DELETE FROM nickname_candidates WHERE nickname = '행복한 고양이'");
        long previousMax = number("SELECT MAX(allocation_order) FROM nickname_candidates");
        seed();
        assertThat(number("SELECT allocation_order FROM nickname_candidates WHERE nickname = '행복한 고양이'"))
                .isEqualTo(previousMax + 1);
        assertThat(number("SELECT COUNT(*) FROM nickname_candidates")).isEqualTo(10_000);
    }

    @Test
    @DisplayName("기존 활성 회원의 이름은 점유하고 탈퇴 회원의 이름은 비워 두며 사용자 프로필을 바꾸지 않는다")
    void reservesExistingActiveNicknamesOnly() {
        UUID active = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        insertUser(active, "신나는 수달", false);
        insertUser(deleted, "행복한 고양이", true);
        flyway("22").migrate();

        assertThat(jdbc.queryForObject("""
                SELECT assigned_user_id FROM nickname_candidates WHERE nickname = '신나는 수달'
                """, UUID.class)).isEqualTo(active);
        assertThat(jdbc.queryForObject("""
                SELECT assigned_user_id FROM nickname_candidates WHERE nickname = '행복한 고양이'
                """, UUID.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE id = ?", String.class, active))
                .isEqualTo("신나는 수달");
        assertThat(jdbc.queryForObject("SELECT profile_image_code FROM users WHERE id = ?", Short.class, active))
                .isEqualTo((short) 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {" 앞공백", "뒤공백 ", "연속  공백", "탭\t문자", "줄\n바꿈", "특수!", "이모지😀", "가나다라마바사아자차카", "가"})
    @DisplayName("직접 SQL로 등록해도 길이·공백·문자·NFC 규칙을 우회하지 못한다")
    void rejectsInvalidCandidate(String nickname) {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO nickname_candidates (nickname, allocation_order) VALUES (?, 1)
                """, nickname)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertUser(UUID id, String nickname, boolean deleted) {
        jdbc.update("""
                INSERT INTO users (id, name, nickname, profile_image_code, deleted_at)
                VALUES (?, '기존회원', ?, 1, CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END)
                """, id, nickname, deleted);
    }

    private long number(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private void seed() {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V22__seed_nickname_candidates.sql"))
                .execute(dataSource);
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .target(target)
                .cleanDisabled(false)
                .load();
    }
}
