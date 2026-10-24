package com.harudle.auth.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.auth.domain.exception.InvalidNicknameException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class NicknamePolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"신나는 하루", "Green123", "초록 Green1", "ㄱㄴㅏㅣ", "가"})
    @DisplayName("한글 음절과 자모, 영문, 숫자 및 중간 공백을 허용한다")
    void acceptsAllowedCharacters(String nickname) {
        assertThat(NicknamePolicy.normalizeAndValidate(nickname)).isEqualTo(nickname);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "하루🙂", "하루👨‍👩‍👧", "하루❤", "하루1️⃣", "하루!", "하루_", "하루-", "하루.",
            "하루@", "하루/", "하루\\", "하루\u00A0두들", "하루\u200B두들", "하루\u0000",
            "하루\u000B두들", "하루\u000C두들", "하루\u2028두들", "하루　두들", "café", "一天", "はる"
    })
    @DisplayName("이모지, 특수문자, 보이지 않는 문자와 허용 범위 밖의 문자를 거부한다")
    void rejectsDisallowedCharacters(String nickname) {
        assertThatThrownBy(() -> NicknamePolicy.normalizeAndValidate(nickname))
                .isInstanceOf(InvalidNicknameException.class)
                .hasMessage("닉네임은 한글, 영문, 숫자와 공백만 사용할 수 있습니다.");
    }

    @Test
    @DisplayName("분해된 한글은 NFC 정규화 후 허용 문자를 검사한다")
    void normalizesBeforeValidatingCharacters() {
        assertThat(NicknamePolicy.normalizeAndValidate("\u1100\u1161".repeat(10)))
                .isEqualTo("가".repeat(10));
    }

    @ParameterizedTest
    @ValueSource(strings = {"신나는 하루", "신나는 하루 두들"})
    @DisplayName("중간의 일반 공백이 연속되지 않으면 그대로 유지한다")
    void preservesSingleInternalSpaces(String nickname) {
        assertThat(NicknamePolicy.normalizeAndValidate(nickname)).isEqualTo(nickname);
    }

    @ParameterizedTest
    @ValueSource(strings = {"신나는  하루", "신나는   하루", "신나는    하루", "신  나는 하루"})
    @DisplayName("중간에 연속된 일반 공백이 있으면 수정 안내 오류를 반환한다")
    void rejectsConsecutiveSpaces(String nickname) {
        assertThatThrownBy(() -> NicknamePolicy.normalizeAndValidate(nickname))
                .isInstanceOf(InvalidNicknameException.class)
                .hasMessage("닉네임에는 공백을 연속으로 사용할 수 없습니다.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            " 신나는 하루", "신나는 하루 ", "  신나는 하루", "신나는 하루  ", "  신나는 하루  "
    })
    @DisplayName("앞뒤 공백이 있으면 자르지 않고 수정 안내 오류를 반환한다")
    void rejectsOuterSpaces(String nickname) {
        assertThatThrownBy(() -> NicknamePolicy.normalizeAndValidate(nickname))
                .isInstanceOf(InvalidNicknameException.class)
                .hasMessage("닉네임 앞뒤의 공백을 제거해 주세요.");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "  "})
    @DisplayName("값이 없거나 공백으로만 구성된 닉네임은 거부한다")
    void rejectsMissingNicknames(String nickname) {
        assertThatThrownBy(() -> NicknamePolicy.normalizeAndValidate(nickname))
                .isInstanceOf(InvalidNicknameException.class)
                .hasMessage("닉네임은 필수입니다.");
    }

    @Test
    @DisplayName("중간의 일반 공백도 닉네임의 10자 제한에 포함한다")
    void countsInternalSpacesTowardsLengthLimit() {
        String tenCharacters = "가".repeat(4) + " " + "나".repeat(5);
        String elevenCharacters = "가".repeat(4) + " " + "나".repeat(6);

        assertThat(NicknamePolicy.normalizeAndValidate(tenCharacters)).isEqualTo(tenCharacters);
        assertThatThrownBy(() -> NicknamePolicy.normalizeAndValidate(elevenCharacters))
                .isInstanceOf(InvalidNicknameException.class)
                .hasMessage("닉네임은 1자 이상 10자 이하여야 합니다.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "신나는\t하루", "\t신나는 하루", "신나는 하루\t",
            "신나는\n하루", "\n신나는 하루", "신나는 하루\n",
            "신나는\r하루", "\r신나는 하루", "신나는 하루\r",
            "신나는\r\n하루", "\r\n신나는 하루", "신나는 하루\r\n"
    })
    @DisplayName("탭과 줄바꿈은 닉네임의 앞뒤 또는 중간 어디에 있어도 거부한다")
    void rejectsTabsAndLineBreaksAtAnyPosition(String nickname) {
        assertThatThrownBy(() -> NicknamePolicy.normalizeAndValidate(nickname))
                .isInstanceOf(InvalidNicknameException.class)
                .hasMessage("닉네임에는 탭이나 줄바꿈을 사용할 수 없습니다.");
    }
}
