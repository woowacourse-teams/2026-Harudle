package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.auth.application.OAuthLoginCommand;
import com.harudle.auth.domain.OAuthProvider;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

class KakaoLoginCommandMapperTest {

    private KakaoLoginCommandMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new KakaoLoginCommandMapper(new ObjectMapper());
    }

    @Test
    @DisplayName("카카오 사용자 정보를 OAuth 로그인 명령으로 변환한다")
    void mapsKakaoUserInfo() {
        Map<String, Object> attributes = Map.of(
                "id", 12345L,
                "kakao_account", Map.of(
                        "email", "user@example.com",
                        "profile", Map.of(
                                "nickname", "하루들"
                        )
                )
        );

        OAuthLoginCommand command = mapper.map(attributes);

        assertThat(command).isEqualTo(new OAuthLoginCommand(
                OAuthProvider.KAKAO,
                "12345",
                "user@example.com",
                "하루들"
        ));
    }

    @Test
    @DisplayName("카카오 계정 정보가 없어도 로그인 명령을 생성한다")
    void mapsWithoutKakaoAccount() {
        Map<String, Object> attributes = Map.of("id", 12345L);

        OAuthLoginCommand command = mapper.map(attributes);

        assertThat(command.providerEmail()).isNull();
        assertThat(command.displayName()).isNull();
    }

    @Test
    @DisplayName("카카오 프로필이 없어도 로그인 명령을 생성한다")
    void mapsWithoutKakaoProfile() {
        Map<String, Object> attributes = Map.of(
                "id", 12345L,
                "kakao_account", Map.of(
                        "email", "user@example.com"
                )
        );

        OAuthLoginCommand command = mapper.map(attributes);

        assertThat(command.providerEmail()).isEqualTo("user@example.com");
        assertThat(command.displayName()).isNull();
    }

    @Test
    @DisplayName("카카오 닉네임이 없어도 로그인 명령을 생성한다")
    void mapsWithoutKakaoNickname() {
        Map<String, Object> attributes = Map.of(
                "id", 12345L,
                "kakao_account", Map.of(
                        "profile", Map.of()
                )
        );

        OAuthLoginCommand command = mapper.map(attributes);

        assertThat(command.displayName()).isNull();
    }

    @Test
    @DisplayName("카카오 이메일이 없어도 로그인 명령을 생성한다")
    void mapsWithoutKakaoEmail() {
        Map<String, Object> attributes = Map.of(
                "id", 12345L,
                "kakao_account", Map.of(
                        "profile", Map.of(
                                "nickname", "하루들"
                        )
                )
        );

        OAuthLoginCommand command = mapper.map(attributes);

        assertThat(command.providerEmail()).isNull();
        assertThat(command.displayName()).isEqualTo("하루들");
    }

    @Test
    @DisplayName("빈 이메일과 닉네임은 로그인 명령에서 null로 정리한다")
    void normalizesBlankKakaoAttributes() {
        Map<String, Object> attributes = Map.of(
                "id", 12345L,
                "kakao_account", Map.of(
                        "email", "   ",
                        "profile", Map.of(
                                "nickname", "   "
                        )
                )
        );

        OAuthLoginCommand command = mapper.map(attributes);

        assertThat(command.providerEmail()).isNull();
        assertThat(command.displayName()).isNull();
    }

    @Test
    @DisplayName("알 수 없는 카카오 응답 필드는 무시한다")
    void ignoresUnknownKakaoAttributes() {
        Map<String, Object> attributes = Map.of(
                "id", 12345L,
                "connected_at", "2026-08-12T10:00:00Z",
                "properties", Map.of(
                        "unknown", "value"
                )
        );

        OAuthLoginCommand command = mapper.map(attributes);

        assertThat(command.providerSubject()).isEqualTo("12345");
    }

    @Test
    @DisplayName("카카오 사용자 정보가 null이면 로그인 명령을 생성할 수 없다")
    void rejectsNullAttributes() {
        assertThatThrownBy(() -> mapper.map(null))
                .isInstanceOf(InvalidOAuthProfileException.class);
    }

    @Test
    @DisplayName("카카오 사용자 식별자가 없으면 로그인 명령을 생성할 수 없다")
    void rejectsMissingKakaoId() {
        Map<String, Object> attributes = Map.of(
                "kakao_account", Map.of()
        );

        assertThatThrownBy(() -> mapper.map(attributes))
                .isInstanceOf(InvalidOAuthProfileException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, 0L})
    @DisplayName("카카오 사용자 식별자가 양수가 아니면 로그인 명령을 생성할 수 없다")
    void rejectsNonPositiveKakaoId(long id) {
        Map<String, Object> attributes = Map.of("id", id);

        assertThatThrownBy(() -> mapper.map(attributes))
                .isInstanceOf(InvalidOAuthProfileException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, Long.MAX_VALUE})
    @DisplayName("카카오 사용자 식별자가 양수이면 문자열 식별자로 변환한다")
    void mapsPositiveKakaoId(long id) {
        Map<String, Object> attributes = Map.of("id", id);

        OAuthLoginCommand command = mapper.map(attributes);

        assertThat(command.providerSubject()).isEqualTo(Long.toString(id));
    }

    @Test
    @DisplayName("카카오 계정 정보의 구조가 올바르지 않으면 로그인 명령을 생성할 수 없다")
    void rejectsMalformedKakaoAccount() {
        Map<String, Object> attributes = Map.of(
                "id", 12345L,
                "kakao_account", "invalid"
        );

        assertThatThrownBy(() -> mapper.map(attributes))
                .isInstanceOf(InvalidOAuthProfileException.class)
                .hasRootCauseInstanceOf(JacksonException.class);
    }

}
