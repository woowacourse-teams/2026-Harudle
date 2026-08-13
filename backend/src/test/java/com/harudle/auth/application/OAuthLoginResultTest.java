package com.harudle.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OAuthLoginResultTest {

    @Test
    @DisplayName("로그인한 사용자의 식별자를 보관한다")
    void keepsUserId() {
        UUID userId = UUID.randomUUID();

        OAuthLoginResult result = new OAuthLoginResult(userId);

        assertThat(result.userId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("사용자 식별자가 없으면 로그인 결과를 생성할 수 없다")
    void rejectsNullUserId() {
        assertThatThrownBy(() -> new OAuthLoginResult(null))
                .isInstanceOf(NullPointerException.class);
    }

}
