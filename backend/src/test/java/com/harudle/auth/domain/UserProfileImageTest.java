package com.harudle.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

public class UserProfileImageTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-20T00:00:00Z");
    private static final Instant CHANGED_AT = CREATED_AT.plusSeconds(60);
    private static final String IMAGE_OBJECT_KEY = "harudle/profile-images/dev/test-user/image.webp";

    @ParameterizedTest
    @ValueSource(ints = {3, 10})
    @DisplayName("기본 이미지를 선택하면 개인 사진 연결을 해제한다")
    void changesDefaultImageAndClearsUploadedImage(int imageCode) {
        User user = createUserWithProfile();
        user.changeUploadedProfileImage(IMAGE_OBJECT_KEY, CHANGED_AT);

        user.changeDefaultProfileImage(imageCode, CHANGED_AT.plusSeconds(60));

        assertThat(user.getProfileImageCode()).isEqualTo((short) imageCode);
        assertThat(user.getProfileImageObjectKey()).isNull();
        assertThat(user.getNickname()).isEqualTo("재미있는 하루");
    }

    @Test
    @DisplayName("개인 사진을 변경해도 기본 이미지 번호와 닉네임은 유지한다")
    void changesUploadedImageWithoutChangingDefaultImageOrNickname() {
        User user = createUserWithProfile();

        user.changeUploadedProfileImage(IMAGE_OBJECT_KEY, CHANGED_AT);

        assertThat(user.getProfileImageObjectKey()).isEqualTo(IMAGE_OBJECT_KEY);
        assertThat(user.getProfileImageCode()).isEqualTo((short) 3);
        assertThat(user.getNickname()).isEqualTo("재미있는 하루");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 11})
    @DisplayName("잘못된 기본 이미지 번호를 전달하면 기존 프로필을 유지한다")
    void preservesProfileWhenDefaultImageCodeIsInvalid(int imageCode) {
        User user = createUserWithProfile();
        user.changeUploadedProfileImage(IMAGE_OBJECT_KEY, CHANGED_AT);

        assertThatThrownBy(() -> user.changeDefaultProfileImage(imageCode, CHANGED_AT.plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(user.getProfileImageCode()).isEqualTo((short) 3);
        assertThat(user.getProfileImageObjectKey()).isEqualTo(IMAGE_OBJECT_KEY);
        assertThat(user.getNickname()).isEqualTo("재미있는 하루");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    @DisplayName("사진 경로가 없거나 공백이면 기존 사진을 유지한다")
    void preservesImageWhenUploadedImageKeyIsBlank(String imageObjectKey) {
        User user = createUserWithProfile();
        user.changeUploadedProfileImage(IMAGE_OBJECT_KEY, CHANGED_AT);

        assertThatThrownBy(() -> user.changeUploadedProfileImage(
                imageObjectKey,
                CHANGED_AT.plusSeconds(60)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThat(user.getProfileImageObjectKey()).isEqualTo(IMAGE_OBJECT_KEY);
    }

    @Test
    @DisplayName("사진 경로가 너무 길면 기존 사진을 유지한다")
    void preservesImageWhenUploadedImageKeyIsTooLong() {
        User user = createUserWithProfile();
        user.changeUploadedProfileImage(IMAGE_OBJECT_KEY, CHANGED_AT);

        assertThatThrownBy(() -> user.changeUploadedProfileImage(
                "a".repeat(1025),
                CHANGED_AT.plusSeconds(60)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThat(user.getProfileImageObjectKey()).isEqualTo(IMAGE_OBJECT_KEY);
    }

    @Test
    @DisplayName("프로필이 배정되지 않은 회원은 이미지를 변경할 수 없다")
    void rejectsImageChangesBeforeProfileAssignment() {
        User user = new User(null, "김초록", CREATED_AT);

        assertThatThrownBy(() -> user.changeDefaultProfileImage(1, CHANGED_AT))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> user.changeUploadedProfileImage(IMAGE_OBJECT_KEY, CHANGED_AT))
                .isInstanceOf(IllegalStateException.class);

        assertThat(user.getProfileImageCode()).isNull();
        assertThat(user.getProfileImageObjectKey()).isNull();
    }

    private User createUserWithProfile() {
        User user = new User(null, "김초록", CREATED_AT);
        user.assignInitialPublicProfile("재미있는 하루", 3, CREATED_AT);
        return user;
    }
}
