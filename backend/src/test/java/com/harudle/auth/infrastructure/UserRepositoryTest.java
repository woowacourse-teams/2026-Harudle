package com.harudle.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.TestcontainersConfiguration;
import com.harudle.auth.domain.User;
import com.harudle.auth.domain.exception.InvalidNicknameException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-10T00:00:00Z");
    private static final Instant PROFILE_ASSIGNED_AT = CREATED_AT.plusSeconds(60);
    private static final Instant NICKNAME_CHANGED_AT = CREATED_AT.plusSeconds(120);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 5, 6, 10})
    @DisplayName("공개 프로필과 이미지 코드를 저장하고 중간의 일반 공백 한 칸을 유지해 조회한다")
    void persistsPublicProfile(int imageCode) {
        User user = new User("green@example.com", "김초록", CREATED_AT);
        user.assignInitialPublicProfile("재미있는 하루", imageCode, PROFILE_ASSIGNED_AT);

        User savedUser = userRepository.saveAndFlush(user);
        entityManager.clear();

        User reloadedUser = findUser(savedUser.getId());

        assertThat(reloadedUser.getNickname()).isEqualTo("재미있는 하루");
        assertThat(reloadedUser.getProfileImageCode()).isEqualTo((short) imageCode);
        assertThat(reloadedUser.getName()).isEqualTo("김초록");
        assertThat(reloadedUser.getPrimaryEmail()).isEqualTo("green@example.com");
        assertThat(reloadedUser.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(findUpdatedAt(reloadedUser.getId())).isEqualTo(PROFILE_ASSIGNED_AT);
    }

    @ParameterizedTest
    @MethodSource("validBoundaryNicknames")
    @DisplayName("허용 문자로 구성된 1자와 10자 닉네임을 저장한다")
    void persistsNicknamesAtLengthBoundaries(String nickname) {
        User user = new User("green@example.com", "김초록", CREATED_AT);
        user.assignInitialPublicProfile(nickname, 1, PROFILE_ASSIGNED_AT);

        User savedUser = userRepository.saveAndFlush(user);
        entityManager.clear();

        assertThat(findUser(savedUser.getId()).getNickname()).isEqualTo(nickname);
    }

    @Test
    @DisplayName("닉네임 변경과 변경 시각을 저장하고 기존 이미지는 유지한다")
    void persistsNicknameChangeWithoutChangingImage() {
        User savedUser = saveUserWithPublicProfile("재미있는 하루", 3);
        entityManager.clear();

        User user = findUser(savedUser.getId());
        user.changeNickname("배고픈 두들", NICKNAME_CHANGED_AT);
        flushAndClear();

        User reloadedUser = findUser(savedUser.getId());

        assertThat(reloadedUser.getNickname()).isEqualTo("배고픈 두들");
        assertThat(reloadedUser.getProfileImageCode()).isEqualTo((short) 3);
        assertThat(reloadedUser.getName()).isEqualTo("김초록");
        assertThat(reloadedUser.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(findUpdatedAt(reloadedUser.getId())).isEqualTo(NICKNAME_CHANGED_AT);
    }

    @ParameterizedTest
    @ValueSource(strings = {" 배고픈 두들", "배고픈 두들 ", "배고픈  두들", "배고픈   두들"})
    @DisplayName("금지된 공백으로 닉네임 변경이 실패하면 DB의 프로필과 변경 시각을 유지한다")
    void preservesStoredProfileWhenNicknameHasInvalidWhitespace(String nickname) {
        User savedUser = saveUserWithPublicProfile("재미있는 하루", 3);
        entityManager.clear();

        User user = findUser(savedUser.getId());
        assertThatThrownBy(() -> user.changeNickname(nickname, NICKNAME_CHANGED_AT))
                .isInstanceOf(InvalidNicknameException.class);
        flushAndClear();

        User reloadedUser = findUser(savedUser.getId());
        assertThat(reloadedUser.getNickname()).isEqualTo("재미있는 하루");
        assertThat(reloadedUser.getProfileImageCode()).isEqualTo((short) 3);
        assertThat(findUpdatedAt(reloadedUser.getId())).isEqualTo(PROFILE_ASSIGNED_AT);
    }

    @Test
    @DisplayName("이미지가 먼저 배정된 기존 회원에게 닉네임을 배정해도 이미지는 유지한다")
    void preservesPreviouslyAssignedImage() {
        User savedUser = saveUser();
        jdbcTemplate.update(
                "UPDATE users SET profile_image_code = ? WHERE id = ?",
                2,
                savedUser.getId()
        );
        entityManager.clear();

        User user = findUser(savedUser.getId());
        assertThat(user.getNickname()).isNull();
        assertThat(user.getProfileImageCode()).isEqualTo((short) 2);

        user.assignInitialPublicProfile("재미있는 하루", 5, PROFILE_ASSIGNED_AT);
        flushAndClear();

        User reloadedUser = findUser(savedUser.getId());

        assertThat(reloadedUser.getNickname()).isEqualTo("재미있는 하루");
        assertThat(reloadedUser.getProfileImageCode()).isEqualTo((short) 2);
        assertThat(findUpdatedAt(reloadedUser.getId())).isEqualTo(PROFILE_ASSIGNED_AT);
    }

    @Test
    @DisplayName("공개 프로필이 미배정인 기존 회원과 게스트를 저장할 수 있다")
    void persistsUnassignedProfilesForExistingAndGuestUsers() {
        User member = saveUser();
        User guest = userRepository.saveAndFlush(new User(null, "게스트", CREATED_AT));
        entityManager.clear();

        User reloadedMember = findUser(member.getId());
        User reloadedGuest = findUser(guest.getId());

        assertThat(reloadedMember.getNickname()).isNull();
        assertThat(reloadedMember.getProfileImageCode()).isNull();
        assertThat(reloadedGuest.getPrimaryEmail()).isNull();
        assertThat(reloadedGuest.getNickname()).isNull();
        assertThat(reloadedGuest.getProfileImageCode()).isNull();
    }

    @Test
    @DisplayName("탈퇴하지 않은 회원끼리 같은 닉네임을 저장할 수 없다")
    void rejectsDuplicateNicknamesForActiveUsers() {
        saveUserWithPublicProfile("재미있는 하루", 1);

        User anotherUser = new User("another@example.com", "다른 회원", CREATED_AT);
        anotherUser.assignInitialPublicProfile("재미있는 하루", 2, PROFILE_ASSIGNED_AT);

        assertThatThrownBy(() -> userRepository.saveAndFlush(anotherUser))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_users_active_nickname");
    }

    @Test
    @DisplayName("탈퇴한 회원의 닉네임은 다른 회원이 사용할 수 있다")
    void allowsReusingNicknameAfterUserDeletion() {
        User deletedUser = saveUserWithPublicProfile("재미있는 하루", 1);
        jdbcTemplate.update(
                "UPDATE users SET deleted_at = ? WHERE id = ?",
                Timestamp.from(NICKNAME_CHANGED_AT),
                deletedUser.getId()
        );
        entityManager.clear();

        User anotherUser = new User("another@example.com", "다른 회원", CREATED_AT);
        anotherUser.assignInitialPublicProfile("재미있는 하루", 1, NICKNAME_CHANGED_AT);
        User savedUser = userRepository.saveAndFlush(anotherUser);
        entityManager.clear();

        assertThat(findUser(deletedUser.getId()).isDeleted()).isTrue();
        assertThat(findUser(savedUser.getId()).getNickname()).isEqualTo("재미있는 하루");
    }

    @ParameterizedTest
    @MethodSource("invalidDatabaseNicknames")
    @DisplayName("도메인 검증을 거치지 않은 잘못된 닉네임도 DB CHECK 제약이 거부한다")
    void rejectsInvalidNicknamesAtDatabaseBoundary(String nickname) {
        User user = saveUser();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE users SET nickname = ? WHERE id = ?",
                nickname,
                user.getId()
        ))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_nickname");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 11})
    @DisplayName("도메인 검증을 거치지 않은 잘못된 이미지 코드도 DB CHECK 제약이 거부한다")
    void rejectsInvalidImageCodesAtDatabaseBoundary(int imageCode) {
        User user = saveUser();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE users SET profile_image_code = ? WHERE id = ?",
                imageCode,
                user.getId()
        ))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_profile_image_code");
    }

    private static Stream<String> validBoundaryNicknames() {
        return Stream.of("가", "가".repeat(10), "A".repeat(10), "1".repeat(10), "ㄱㄴㅏㅣ");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "하루🙂", "하루❤", "하루1️⃣", "하루!", "하루_", "하루-", "하루.", "하루@", "하루/",
            "하루\u00A0두들", "하루\u200B두들", "하루\u000B두들", "하루\u000C두들",
            "하루\u2028두들", "하루　두들", "café", "一天", "はる"
    })
    @DisplayName("직접 SQL로 저장해도 허용 범위 밖의 닉네임 문자는 DB에서 거부한다")
    void rejectsDisallowedCharactersAtDatabaseBoundary(String nickname) {
        User user = saveUser();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE users SET nickname = ? WHERE id = ?",
                nickname,
                user.getId()
        ))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_nickname_characters");
    }

    private static Stream<String> invalidDatabaseNicknames() {
        return Stream.of(
                "", " 재미있는 하루", "재미있는 하루 ", "가".repeat(11), "\u1100\u1161",
                "신나는  하루", "신나는   하루",
                "신나는\t하루", "\t신나는 하루", "신나는 하루\t",
                "신나는\n하루", "\n신나는 하루", "신나는 하루\n",
                "신나는\r하루", "\r신나는 하루", "신나는 하루\r",
                "신나는\r\n하루"
        );
    }

    private User saveUser() {
        return userRepository.saveAndFlush(new User("green@example.com", "김초록", CREATED_AT));
    }

    private User saveUserWithPublicProfile(String nickname, int imageCode) {
        User user = new User("green@example.com", "김초록", CREATED_AT);
        user.assignInitialPublicProfile(nickname, imageCode, PROFILE_ASSIGNED_AT);
        return userRepository.saveAndFlush(user);
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId).orElseThrow();
    }

    private Instant findUpdatedAt(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM users WHERE id = ?",
                (resultSet, rowNum) -> resultSet.getTimestamp("updated_at").toInstant(),
                userId
        );
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
