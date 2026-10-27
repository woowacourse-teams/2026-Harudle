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

class GoogleLoginCommandMapperTest {

    private GoogleLoginCommandMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new GoogleLoginCommandMapper(new ObjectMapper());
    }

    @Test
    @DisplayName("구글 사용자 정보를 OAuth 로그인 명령으로 변환한다")
    void mapsGoogleUserInfo() {
        Map<String, Object> attributes = Map.of(
                "sub", "google-user-12345",
                "email", "user@example.com",
                "name", "하루들"
        );

        OAuthLoginCommand command = mapper.map(attributes);

        assertThat(command).isEqualTo(new OAuthLoginCommand(
                OAuthProvider.GOOGLE,
                "google-user-12345",
                "user@example.com",
                "하루들"
        ));
    }

    @Test
    @DisplayName("구글 이메일과 이름이 없어도 고유값으로 로그인 명령을 생성한다")
    void mapsWithoutEmailAndName() {
        OAuthLoginCommand command = mapper.map(Map.of("sub", "12345"));

        assertThat(command.providerSubject()).isEqualTo("12345");
        assertThat(command.providerEmail()).isNull();
        assertThat(command.displayName()).isNull();
    }

    @Test
    @DisplayName("구글 이름이 없어도 이메일과 고유값으로 로그인 명령을 생성한다")
    void mapsWithoutName() {
        OAuthLoginCommand command = mapper.map(Map.of(
                "sub", "12345",
                "email", "user@example.com"
        ));

        assertThat(command.providerSubject()).isEqualTo("12345");
        assertThat(command.providerEmail()).isEqualTo("user@example.com");
        assertThat(command.displayName()).isNull();
    }

    @Test
    @DisplayName("빈 구글 이메일과 이름은 로그인 명령에서 null로 정리한다")
    void normalizesBlankGoogleAttributes() {
        OAuthLoginCommand command = mapper.map(Map.of(
                "sub", "12345",
                "email", "   ",
                "name", "   "
        ));

        assertThat(command.providerEmail()).isNull();
        assertThat(command.displayName()).isNull();
    }

    @Test
    @DisplayName("알 수 없는 구글 응답 필드는 무시한다")
    void ignoresUnknownGoogleAttributes() {
        OAuthLoginCommand command = mapper.map(Map.of(
                "sub", "12345",
                "email_verified", true,
                "picture", "https://example.com/profile.png"
        ));

        assertThat(command.providerSubject()).isEqualTo("12345");
    }

    @Test
    @DisplayName("구글 사용자 정보가 null이면 로그인 명령을 생성할 수 없다")
    void rejectsNullAttributes() {
        assertThatThrownBy(() -> mapper.map(null))
                .isInstanceOf(InvalidOAuthProfileException.class);
    }

    @Test
    @DisplayName("구글 고유값이 없으면 이메일이 있어도 로그인 명령을 생성할 수 없다")
    void rejectsMissingSubject() {
        assertThatThrownBy(() -> mapper.map(Map.of("email", "user@example.com")))
                .isInstanceOf(InvalidOAuthProfileException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    @DisplayName("구글 고유값이 비어 있으면 로그인 명령을 생성할 수 없다")
    void rejectsBlankSubject(String subject) {
        assertThatThrownBy(() -> mapper.map(Map.of("sub", subject)))
                .isInstanceOf(InvalidOAuthProfileException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("구글 응답의 구조가 올바르지 않으면 로그인 명령을 생성할 수 없다")
    void rejectsMalformedGoogleAttributes() {
        assertThatThrownBy(() -> mapper.map(Map.of("sub", Map.of("id", "12345"))))
                .isInstanceOf(InvalidOAuthProfileException.class)
                .hasRootCauseInstanceOf(JacksonException.class);
    }

}
