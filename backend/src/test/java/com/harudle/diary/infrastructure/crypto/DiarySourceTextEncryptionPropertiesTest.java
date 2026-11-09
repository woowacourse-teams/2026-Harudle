package com.harudle.diary.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiarySourceTextEncryptionPropertiesTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    @DisplayName("키 설정을 복사해 외부 변경을 막고 문자열 출력에서 비밀값을 숨긴다")
    void copiesKeyRingAndDoesNotExposeSecretsInToString() {
        Map<String, String> mutableKeys = new HashMap<>(Map.of("current", KEY));
        DiarySourceTextEncryptionProperties properties = new DiarySourceTextEncryptionProperties(
                "current", mutableKeys
        );

        mutableKeys.clear();

        assertThat(properties.keys()).containsExactlyEntriesOf(Map.of("current", KEY));
        assertThatThrownBy(() -> properties.keys().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(properties.toString()).isEqualTo("DiarySourceTextEncryptionProperties[activeKeyId=***, keys=***]")
                .doesNotContain(KEY, "current");
    }

    @Test
    @DisplayName("활성 키가 없거나 키 목록에서 찾을 수 없으면 설정을 거부한다")
    void rejectsAbsentOrUnknownActiveKey() {
        for (String invalidActiveKey : new String[]{null, "", " ", "absent", "invalid:key", "k".repeat(65)}) {
            assertInvalid(invalidActiveKey, Map.of("current", KEY));
        }
        assertInvalid("current", null);
        assertInvalid("current", Map.of());
    }

    @Test
    @DisplayName("비활성 키를 포함해 잘못된 키 식별자가 있으면 설정을 거부한다")
    void rejectsInvalidKeyIdentifiersAnywhereInRing() {
        for (String invalidKeyId : new String[]{"", " ", "invalid:key", "한글", "k".repeat(65)}) {
            Map<String, String> keys = new HashMap<>(Map.of("current", KEY));
            keys.put(invalidKeyId, KEY);
            assertInvalid("current", keys);
        }
        Map<String, String> keys = new HashMap<>(Map.of("current", KEY));
        keys.put(null, KEY);
        assertInvalid("current", keys);
    }

    @Test
    @DisplayName("키가 없거나 Base64 형식 또는 256비트 길이를 만족하지 않으면 거부한다")
    void rejectsAbsentMalformedAndNon256BitKeys() {
        for (String invalidKey : new String[]{"", " ", "not-base64!",
                Base64.getEncoder().encodeToString(new byte[16]),
                Base64.getEncoder().encodeToString(new byte[31]),
                Base64.getEncoder().encodeToString(new byte[33])}) {
            assertInvalid("current", Map.of("current", invalidKey));
        }
        Map<String, String> keys = new HashMap<>();
        keys.put("current", null);
        assertInvalid("current", keys);
    }

    @Test
    @DisplayName("비활성 키도 검증하며 동일한 키를 다른 식별자로 중복 등록할 수 없다")
    void validatesInactiveKeysAndRejectsSameDecodedKeyUnderMultipleIdentifiers() {
        assertInvalid("current", Map.of("current", KEY, "retired", "not-base64!"));
        assertInvalid("current", Map.of("current", KEY, "retired", KEY));
        assertInvalid("current", Map.of("current", KEY, "retired", KEY.replace("=", "")));
    }

    private static void assertInvalid(String activeKeyId, Map<String, String> keys) {
        assertThatThrownBy(() -> new DiarySourceTextEncryptionProperties(activeKeyId, keys))
                .isExactlyInstanceOf(DiarySourceTextCryptoException.class)
                .hasMessage("일기 원문 암호화 처리에 실패했습니다.")
                .hasNoCause();
    }
}
