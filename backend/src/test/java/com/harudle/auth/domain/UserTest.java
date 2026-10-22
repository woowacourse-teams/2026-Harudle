package com.harudle.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UserTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-12T10:00:00Z");

    @Test
    @DisplayName("사용자를 생성한다")
    void createsUser() {
        User user = new User(
                "user@example.com",
                "하루들",
                CREATED_AT
        );

        assertThat(user.getId()).isNotNull();
        assertThat(user.getPrimaryEmail()).isEqualTo("user@example.com");
        assertThat(user.getName()).isEqualTo("하루들");
        assertThat(user.getRole()).isEqualTo(UserRole.USER);
        assertThat(user.isAdmin()).isFalse();
        assertThat(user.getDailyGenerationLimit()).isEqualTo(3);
        assertThat(user.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("소셜 이름이 제공되면 이름만 갱신하고 공개 닉네임과 이미지는 유지한다")
    void updatesNameWithoutChangingPublicProfile() {
        User user = new User(null, "기존 이름", CREATED_AT);
        user.assignInitialPublicProfile("재미있는 하루", 3, CREATED_AT);

        user.updateNameIfProvided("새 이름", CREATED_AT.plusSeconds(60));

        assertThat(user.getName()).isEqualTo("새 이름");
        assertThat(user.getNickname()).isEqualTo("재미있는 하루");
        assertThat(user.getProfileImageCode()).isEqualTo((short) 3);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    @DisplayName("소셜 이름이 제공되지 않으면 기존 이름과 공개 프로필을 유지한다")
    void keepsNameWhenNameIsNotProvided(String name) {
        User user = new User(null, "기존 이름", CREATED_AT);
        user.assignInitialPublicProfile("재미있는 하루", 3, CREATED_AT);

        user.updateNameIfProvided(name, CREATED_AT.plusSeconds(60));

        assertThat(user.getName()).isEqualTo("기존 이름");
        assertThat(user.getNickname()).isEqualTo("재미있는 하루");
        assertThat(user.getProfileImageCode()).isEqualTo((short) 3);
    }

    @Test
    @DisplayName("새로 생성한 사용자는 활성 상태다")
    void createsActiveUser() {
        User user = new User(
                null,
                "사용자",
                CREATED_AT
        );

        assertThat(user.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("일일 생성 한도를 1 이상으로 변경한다")
    void changesDailyGenerationLimit() {
        User user = new User(null, "사용자", CREATED_AT);

        user.changeDailyGenerationLimit(5);

        assertThat(user.getDailyGenerationLimit()).isEqualTo(5);
    }

    @Test
    @DisplayName("1 미만인 일일 생성 한도는 거부한다")
    void rejectsDailyGenerationLimitBelowOne() {
        User user = new User(null, "사용자", CREATED_AT);

        assertThatThrownBy(() -> user.changeDailyGenerationLimit(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("일일 생성 한도는 1 이상이어야 합니다.");
        assertThatThrownBy(() -> user.changeDailyGenerationLimit(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("일일 생성 한도는 1 이상이어야 합니다.");
    }

    @Test
    @DisplayName("최초 공개 프로필을 배정하고 중간의 일반 공백 한 칸을 유지한다")
    void assignsInitialPublicProfile() {
        User user = new User(
                "green@example.com",
                "김초록",
                CREATED_AT
        );

        user.assignInitialPublicProfile(
                "재미있는 하루",
                3,
                CREATED_AT
        );

        assertThat(user.getNickname()).isEqualTo("재미있는 하루");
        assertThat(user.getProfileImageCode()).isEqualTo((short) 3);
        assertThat(user.getName()).isEqualTo("김초록");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6})
    @DisplayName("이미지 코드가 잘못되면 닉네임도 배정하지 않는다")
    void preservesStateWhenImageCodeIsInvalid(int imageCode) {
        User user = new User(null, "김초록", CREATED_AT);

        assertThatThrownBy(() -> user.assignInitialPublicProfile(
                "재미있는 하루",
                imageCode,
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class);

        assertThat(user.getNickname()).isNull();
        assertThat(user.getProfileImageCode()).isNull();
    }

    @Test
    @DisplayName("최초 배정을 다시 요청해도 기존 닉네임과 이미지를 유지한다")
    void rejectsReassignmentWithoutChangingProfile() {
        User user = new User(null, "김초록", CREATED_AT);
        user.assignInitialPublicProfile("재미있는 하루", 3, CREATED_AT);

        assertThatThrownBy(() -> user.assignInitialPublicProfile("배고픈 두들", 5, CREATED_AT.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("공개 프로필은 이미 배정되었습니다.");

        assertThat(user.getNickname()).isEqualTo("재미있는 하루");
        assertThat(user.getProfileImageCode()).isEqualTo((short) 3);
    }

    @Test
    @DisplayName("프로필을 배정하지 않은 회원은 닉네임을 변경할 수 없다")
    void rejectsNicknameChangeBeforeProfileAssignment() {
        User user = new User(null, "김초록", CREATED_AT);

        assertThatThrownBy(() -> user.changeNickname("배고픈 두들", CREATED_AT.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("공개 프로필을 먼저 배정해야 합니다.");

        assertThat(user.getNickname()).isNull();
        assertThat(user.getProfileImageCode()).isNull();
    }

    @Test
    @DisplayName("닉네임을 변경해도 배정된 이미지는 유지한다")
    void changesNicknameWithoutChangingImage() {
        User user = new User(null, "김초록", CREATED_AT);

        user.assignInitialPublicProfile(
                "재미있는 하루",
                3,
                CREATED_AT
        );

        user.changeNickname(
                "배고픈 두들",
                CREATED_AT.plusSeconds(60)
        );

        assertThat(user.getNickname()).isEqualTo("배고픈 두들");
        assertThat(user.getProfileImageCode()).isEqualTo((short) 3);
    }

    @Test
    @DisplayName("NFC 정규화 후 닉네임 길이를 검사한다")
    void normalizesNicknameBeforeCheckingLength() {
        User user = new User(null, "김초록", CREATED_AT);

        // 분해된 "가" 를 10번 입력
        String decomposedNickname = "\u1100\u1161".repeat(10);

        user.assignInitialPublicProfile(
                decomposedNickname,
                1,
                CREATED_AT
        );

        assertThat(user.getNickname()).isEqualTo("가".repeat(10));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            " 신나는 하루", "신나는 하루 ", "  신나는 하루  ",
            "신나는  하루", "신나는   하루",
            "신나는\t하루", "신나는\n하루", "신나는\r하루", "하루🙂", "하루!"
    })
    @DisplayName("금지된 공백이나 문자가 있는 닉네임으로 최초 배정하면 프로필을 변경하지 않는다")
    void preservesStateWhenInitialNicknameIsInvalid(String nickname) {
        User user = new User(null, "김초록", CREATED_AT);

        assertThatThrownBy(() -> user.assignInitialPublicProfile(nickname, 3, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(user.getNickname()).isNull();
        assertThat(user.getProfileImageCode()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            " 신나는 하루", "신나는 하루 ", "  신나는 하루  ",
            "신나는  하루", "신나는   하루",
            "신나는\t하루", "신나는\n하루", "신나는\r하루", "하루🙂", "하루!"
    })
    @DisplayName("금지된 공백이나 문자가 있는 닉네임으로 변경하면 기존 프로필을 유지한다")
    void preservesProfileWhenChangedNicknameIsInvalid(String nickname) {
        User user = new User(null, "김초록", CREATED_AT);
        user.assignInitialPublicProfile("재미있는 하루", 3, CREATED_AT);

        assertThatThrownBy(() -> user.changeNickname(nickname, CREATED_AT.plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(user.getNickname()).isEqualTo("재미있는 하루");
        assertThat(user.getProfileImageCode()).isEqualTo((short) 3);
    }
}
