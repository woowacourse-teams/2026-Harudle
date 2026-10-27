package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.auth.application.OAuthLoginCommand;
import com.harudle.auth.domain.OAuthProvider;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

class AppleLoginCommandMapperTest {

    private final AppleLoginCommandMapper mapper = new AppleLoginCommandMapper(new ObjectMapper());

    @Test
    @DisplayName("애플 사용자 정보는 이름 없이 로그인 명령으로 변환한다")
    void mapsAppleUserInfoWithoutName() {
        OAuthLoginCommand command = mapper.map(Map.of(
                "sub", "apple-user-12345",
                "email", "user@privaterelay.appleid.com",
                "name", "사용하지 않는 이름"
        ));

        assertThat(command).isEqualTo(new OAuthLoginCommand(
                OAuthProvider.APPLE, "apple-user-12345", "user@privaterelay.appleid.com", null
        ));
        assertThat(mapper.supports("apple")).isTrue();
        assertThat(mapper.supports("google")).isFalse();
        assertThat(mapper.supports("kakao")).isFalse();
    }

    @Test
    @DisplayName("애플 이메일이 없어도 고유값으로 로그인 명령을 생성한다")
    void mapsWithoutEmail() {
        assertThat(mapper.map(Map.of("sub", "apple-user-12345")))
                .isEqualTo(new OAuthLoginCommand(OAuthProvider.APPLE, "apple-user-12345", null, null));
    }

    @ParameterizedTest
    @MethodSource("invalidAttributes")
    @DisplayName("애플 사용자 정보나 고유값이 올바르지 않으면 변환을 거절한다")
    void rejectsInvalidAttributes(Map<String, Object> attributes) {
        assertThatThrownBy(() -> mapper.map(attributes))
                .isInstanceOf(InvalidOAuthProfileException.class);
    }

    private static Stream<Map<String, Object>> invalidAttributes() {
        return Stream.of(null, Map.of(), Map.of("sub", " "), Map.of("sub", Map.of("id", "12345")));
    }
}
