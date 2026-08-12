package com.harudle.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.auth.domain.OAuthProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class OAuthLoginCommandTest {

    private static final String PROVIDER_SUBJECT = "12345";
    private static final String PROVIDER_EMAIL = "user@example.com";
    private static final String DISPLAY_NAME = "하루들";

    @Test
    @DisplayName("OAuth 로그인 명령을 생성한다")
    void createsOAuthLoginCommand() {
        OAuthLoginCommand command = createCommand(
                PROVIDER_SUBJECT,
                PROVIDER_EMAIL,
                DISPLAY_NAME
        );

        assertThat(command.provider()).isEqualTo(OAuthProvider.KAKAO);
        assertThat(command.providerSubject()).isEqualTo(PROVIDER_SUBJECT);
        assertThat(command.providerEmail()).isEqualTo(PROVIDER_EMAIL);
        assertThat(command.displayName()).isEqualTo(DISPLAY_NAME);
    }

    @Test
    @DisplayName("OAuth 제공자가 없으면 로그인 명령을 생성할 수 없다")
    void rejectsNullProvider() {
        assertThatThrownBy(() -> new OAuthLoginCommand(
                null,
                PROVIDER_SUBJECT,
                PROVIDER_EMAIL,
                DISPLAY_NAME
        )).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n"})
    @DisplayName("OAuth 사용자 식별자가 비어 있으면 로그인 명령을 생성할 수 없다")
    void rejectsBlankProviderSubject(String providerSubject) {
        assertThatThrownBy(() -> createCommand(
                providerSubject,
                PROVIDER_EMAIL,
                DISPLAY_NAME
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("OAuth 사용자 식별자의 앞뒤 공백을 제거한다")
    void trimsProviderSubject() {
        OAuthLoginCommand command = createCommand(
                " 12345 ",
                PROVIDER_EMAIL,
                DISPLAY_NAME
        );

        assertThat(command.providerSubject()).isEqualTo(PROVIDER_SUBJECT);
    }

    @ParameterizedTest
    @ValueSource(ints = {254, 255})
    @DisplayName("OAuth 사용자 식별자가 허용 길이 이내이면 로그인 명령을 생성한다")
    void acceptsProviderSubjectWithinLimit(int length) {
        String providerSubject = textOfLength(length);

        OAuthLoginCommand command = createCommand(
                providerSubject,
                PROVIDER_EMAIL,
                DISPLAY_NAME
        );

        assertThat(command.providerSubject()).hasSize(length);
    }

    @Test
    @DisplayName("OAuth 사용자 식별자가 255자를 초과하면 로그인 명령을 생성할 수 없다")
    void rejectsProviderSubjectOverLimit() {
        String providerSubject = textOfLength(256);

        assertThatThrownBy(() -> createCommand(
                providerSubject,
                PROVIDER_EMAIL,
                DISPLAY_NAME
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n"})
    @DisplayName("제공자 이메일이 비어 있으면 null로 정리한다")
    void normalizesBlankProviderEmailToNull(String providerEmail) {
        OAuthLoginCommand command = createCommand(
                PROVIDER_SUBJECT,
                providerEmail,
                DISPLAY_NAME
        );

        assertThat(command.providerEmail()).isNull();
    }

    @Test
    @DisplayName("제공자 이메일의 앞뒤 공백을 제거한다")
    void trimsProviderEmail() {
        OAuthLoginCommand command = createCommand(
                PROVIDER_SUBJECT,
                " user@example.com ",
                DISPLAY_NAME
        );

        assertThat(command.providerEmail()).isEqualTo(PROVIDER_EMAIL);
    }

    @ParameterizedTest
    @ValueSource(ints = {319, 320})
    @DisplayName("제공자 이메일이 허용 길이 이내이면 로그인 명령을 생성한다")
    void acceptsProviderEmailWithinLimit(int length) {
        String providerEmail = textOfLength(length);

        OAuthLoginCommand command = createCommand(
                PROVIDER_SUBJECT,
                providerEmail,
                DISPLAY_NAME
        );

        assertThat(command.providerEmail()).hasSize(length);
    }

    @Test
    @DisplayName("제공자 이메일이 320자를 초과하면 로그인 명령을 생성할 수 없다")
    void rejectsProviderEmailOverLimit() {
        String providerEmail = textOfLength(321);

        assertThatThrownBy(() -> createCommand(
                PROVIDER_SUBJECT,
                providerEmail,
                DISPLAY_NAME
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n"})
    @DisplayName("표시 이름이 비어 있으면 null로 정리한다")
    void normalizesBlankDisplayNameToNull(String displayName) {
        OAuthLoginCommand command = createCommand(
                PROVIDER_SUBJECT,
                PROVIDER_EMAIL,
                displayName
        );

        assertThat(command.displayName()).isNull();
    }

    @Test
    @DisplayName("표시 이름의 앞뒤 공백을 제거한다")
    void trimsDisplayName() {
        OAuthLoginCommand command = createCommand(
                PROVIDER_SUBJECT,
                PROVIDER_EMAIL,
                " 하루들 "
        );

        assertThat(command.displayName()).isEqualTo(DISPLAY_NAME);
    }

    @ParameterizedTest
    @ValueSource(ints = {29, 30})
    @DisplayName("표시 이름이 허용 길이 이내이면 그대로 사용한다")
    void keepsDisplayNameWithinLimit(int length) {
        String displayName = textOfLength(length);

        OAuthLoginCommand command = createCommand(
                PROVIDER_SUBJECT,
                PROVIDER_EMAIL,
                displayName
        );

        assertThat(command.displayName()).isEqualTo(displayName);
    }

    @Test
    @DisplayName("표시 이름이 30자를 초과하면 30자로 줄인다")
    void truncatesDisplayNameOverLimit() {
        String displayName = textOfLength(31);

        OAuthLoginCommand command = createCommand(
                PROVIDER_SUBJECT,
                PROVIDER_EMAIL,
                displayName
        );

        assertThat(command.displayName()).isEqualTo(textOfLength(30));
    }

    @Test
    @DisplayName("표시 이름은 이모지를 코드 포인트 기준으로 줄인다")
    void truncatesEmojiDisplayNameByCodePoint() {
        String displayName = "😀".repeat(31);

        OAuthLoginCommand command = createCommand(
                PROVIDER_SUBJECT,
                PROVIDER_EMAIL,
                displayName
        );

        assertThat(command.displayName()).isEqualTo("😀".repeat(30));
        assertThat(command.displayName().codePointCount(0, command.displayName().length()))
                .isEqualTo(30);
    }

    private OAuthLoginCommand createCommand(
            String providerSubject,
            String providerEmail,
            String displayName
    ) {
        return new OAuthLoginCommand(
                OAuthProvider.KAKAO,
                providerSubject,
                providerEmail,
                displayName
        );
    }

    private String textOfLength(int length) {
        return "a".repeat(length);
    }

}
