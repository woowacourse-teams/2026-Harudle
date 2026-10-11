package com.harudle.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.profile.configuration.ProfileConfiguration;
import com.harudle.profile.service.port.PublicProfileReader;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DataJpaTest(showSql = false, properties = {
        "harudle.profile.image-base-url=https://www.harudle.com/profile-images/v1",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProfileConfiguration.class, ProfileImageUrlResolver.class, DefaultPublicProfileReader.class})
class DefaultPublicProfileReaderTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-11T00:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine")
    );

    @Autowired
    private PublicProfileReader reader;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    @Test
    @DisplayName("요청한 회원의 공개 정보만 DB 쿼리 한 번으로 조회한다")
    void readsRequestedProfilesWithOneQuery() {
        User first = saveProfile("신나는 토끼", 1);
        User second = saveProfile("배고픈 판다", 5);
        saveProfile("즐거운 펭귄", 2);
        entityManager.clear();
        statistics.clear();

        Map<UUID, PublicProfileReader.Profile> profiles = reader.readAll(Set.of(first.getId(), second.getId()));

        assertThat(profiles).containsOnlyKeys(first.getId(), second.getId());
        assertThat(profiles.get(first.getId()))
                .isEqualTo(new PublicProfileReader.Profile(first.getId(), "신나는 토끼", imageUrl(1)));
        assertThat(profiles.get(second.getId()))
                .isEqualTo(new PublicProfileReader.Profile(second.getId(), "배고픈 판다", imageUrl(5)));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    @Test
    @DisplayName("빈 회원 ID 목록은 DB 조회 없이 빈 결과를 반환한다")
    void returnsEmptyMapWithoutQueryForEmptyIds() {
        assertThat(reader.readAll(Set.of())).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    @Test
    @DisplayName("존재하지 않는 회원은 결과에서 제외한다")
    void omitsMissingUsers() {
        User user = saveProfile("신나는 토끼", 1);

        assertThat(reader.readAll(Set.of(user.getId(), UUID.randomUUID())))
                .containsOnlyKeys(user.getId());
        assertThat(reader.readAll(Set.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    @DisplayName("탈퇴 회원은 결과에서 제외한다")
    void omitsDeletedUsers() {
        User active = saveProfile("신나는 토끼", 1);
        User deleted = saveProfile("배고픈 판다", 5);
        jdbcTemplate.update("UPDATE users SET deleted_at = ? WHERE id = ?",
                Timestamp.from(CREATED_AT.plusSeconds(60)), deleted.getId());
        entityManager.clear();

        assertThat(reader.readAll(Set.of(active.getId(), deleted.getId())))
                .containsOnlyKeys(active.getId());
    }

    @Test
    @DisplayName("닉네임 또는 이미지 코드가 미배정이면 결과에서 제외하고 OAuth 이름을 대신 공개하지 않는다")
    void omitsIncompleteProfiles() {
        User active = saveProfile("신나는 토끼", 1);
        User unassigned = userRepository.saveAndFlush(new User("legacy@example.com", "실명", CREATED_AT));
        User imageOnly = userRepository.saveAndFlush(new User("image@example.com", "실명", CREATED_AT));
        User nicknameOnly = saveProfile("배고픈 판다", 5);
        jdbcTemplate.update("UPDATE users SET profile_image_code = 3 WHERE id = ?", imageOnly.getId());
        jdbcTemplate.update("UPDATE users SET profile_image_code = NULL WHERE id = ?", nicknameOnly.getId());
        entityManager.clear();

        assertThat(reader.readAll(Set.of(active.getId(), unassigned.getId(), imageOnly.getId(), nicknameOnly.getId())))
                .containsOnlyKeys(active.getId());
    }

    @Test
    @DisplayName("반복 조회해도 회원 프로필과 변경 시각 및 닉네임 후보 점유를 변경하지 않는다")
    void preservesStoredStateOnRepeatedReads() {
        User user = saveProfile("신나는 토끼", 3);
        Set<UUID> userIds = Set.of(user.getId());

        assertThat(reader.readAll(userIds)).isEqualTo(reader.readAll(userIds));
        entityManager.flush();
        entityManager.clear();

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getNickname()).isEqualTo("신나는 토끼");
        assertThat(reloaded.getProfileImageCode()).isEqualTo((short) 3);
        assertThat(jdbcTemplate.queryForObject("SELECT updated_at FROM users WHERE id = ?", Timestamp.class,
                user.getId()).toInstant()).isEqualTo(CREATED_AT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nickname_candidates WHERE assigned_user_id IS NOT NULL", Long.class
        )).isZero();
    }

    @Test
    @DisplayName("닉네임 변경 후 다시 조회하면 최신 닉네임과 기존 이미지 주소를 반환한다")
    void readsLatestNicknameAfterChange() {
        User user = saveProfile("신나는 토끼", 3);
        Set<UUID> userIds = Set.of(user.getId());
        PublicProfileReader.Profile before = reader.readAll(userIds).get(user.getId());

        user.changeNickname("즐거운 펭귄", CREATED_AT.plusSeconds(60));
        entityManager.flush();
        entityManager.clear();

        PublicProfileReader.Profile after = reader.readAll(userIds).get(user.getId());
        assertThat(before.nickname()).isEqualTo("신나는 토끼");
        assertThat(after.nickname()).isEqualTo("즐거운 펭귄");
        assertThat(after.profileImageUrl()).isEqualTo(before.profileImageUrl());
    }

    @Test
    @DisplayName("회원들이 같은 이미지 코드를 사용하면 같은 이미지 URL을 반환한다")
    void sharesImageUrlForSameCode() {
        User first = saveProfile("신나는 토끼", 3);
        User second = saveProfile("배고픈 판다", 3);

        Map<UUID, PublicProfileReader.Profile> profiles = reader.readAll(Set.of(first.getId(), second.getId()));

        assertThat(profiles.get(first.getId()).profileImageUrl())
                .isEqualTo(profiles.get(second.getId()).profileImageUrl());
    }

    @Test
    @DisplayName("null 회원 ID 목록은 DB 조회 전에 거부한다")
    void rejectsNullIds() {
        assertThatThrownBy(() -> reader.readAll(null)).isInstanceOf(NullPointerException.class);
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    @Test
    @DisplayName("목록에 null 회원 ID가 있으면 DB 조회 전에 거부한다")
    void rejectsNullIdElement() {
        Set<UUID> userIds = new HashSet<>();
        userIds.add(null);

        assertThatThrownBy(() -> reader.readAll(userIds)).isInstanceOf(IllegalArgumentException.class);
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    private User saveProfile(String nickname, int imageCode) {
        User user = new User("green@example.com", "실명", CREATED_AT);
        user.assignInitialPublicProfile(nickname, imageCode, CREATED_AT);
        return userRepository.saveAndFlush(user);
    }

    private URI imageUrl(int imageCode) {
        return URI.create("https://www.harudle.com/profile-images/v1/" + imageCode + ".png");
    }
}
