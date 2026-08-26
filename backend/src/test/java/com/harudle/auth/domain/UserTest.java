package com.harudle.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("일일 생성 한도를 0 이상으로 변경한다")
    void changesDailyGenerationLimit() {
        User user = new User(null, "사용자", CREATED_AT);

        user.changeDailyGenerationLimit(5);

        assertThat(user.getDailyGenerationLimit()).isEqualTo(5);
    }

    @Test
    @DisplayName("음수인 일일 생성 한도는 거부한다")
    void rejectsNegativeDailyGenerationLimit() {
        User user = new User(null, "사용자", CREATED_AT);

        assertThatThrownBy(() -> user.changeDailyGenerationLimit(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("일일 생성 한도는 0 이상이어야 합니다.");
    }
}
