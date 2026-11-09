package com.harudle.diary.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.diary.domain.DiarySourceTextCipher;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiaryRequestFingerprintProtectionTest {

    private static final UUID DIARY_ID = UUID.fromString("f600df8a-7306-48c9-a3b5-c6a62efc4484");
    private static final UUID USER_ID = UUID.fromString("e053fe37-0b12-47a9-9ea7-7af861f79f36");
    private static final String OLD_KEY = encodedKey((byte) 1);
    private static final String NEW_KEY = encodedKey((byte) 2);
    private static final String RAW_FINGERPRINT = "12".repeat(32);

    private final DiarySourceTextCipher cipher = cipher("old", Map.of("old", OLD_KEY));

    @Test
    @DisplayName("목적별 파생 키와 일기 문맥으로 원문 해시를 보호하고 동일한 요청에는 같은 값을 반환한다")
    void protectsSourceHashWithPurposeDerivedKeyAndStableRowContext() {
        String stored = cipher.encrypt(DIARY_ID, USER_ID, "짧은 일기");

        String protectedFingerprint = protect(cipher, stored, RAW_FINGERPRINT);

        // Independently calculated with Python's standard-library HMAC implementation.
        assertThat(protectedFingerprint).isEqualTo("0c7a43d30a596807e6bd5b30cd6b3d16933463db1749e5f85ada94b6a577cb77")
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(RAW_FINGERPRINT);
        assertThat(protect(cipher, stored, RAW_FINGERPRINT)).isEqualTo(protectedFingerprint);
        String freshlyEncrypted = cipher.encrypt(DIARY_ID, USER_ID, "짧은 일기");
        assertThat(protect(cipher, freshlyEncrypted, RAW_FINGERPRINT)).isEqualTo(protectedFingerprint);
        assertThat(protect(cipher, stored, "34".repeat(32))).isNotEqualTo(protectedFingerprint);
    }

    @Test
    @DisplayName("원문 해시가 같아도 일기나 사용자가 다르면 서로 다른 지문을 만든다")
    void bindsFingerprintToDiaryAndUserEvenWhenRawHashIsIdentical() {
        String stored = cipher.encrypt(DIARY_ID, USER_ID, "원문");
        String expected = protect(cipher, stored, RAW_FINGERPRINT);

        assertThat(cipher.protectRequestFingerprint(UUID.randomUUID(), USER_ID, stored, 1, RAW_FINGERPRINT))
                .isNotEqualTo(expected);
        assertThat(cipher.protectRequestFingerprint(DIARY_ID, UUID.randomUUID(), stored, 1, RAW_FINGERPRINT))
                .isNotEqualTo(expected);
    }

    @Test
    @DisplayName("키 값이나 키 식별자가 달라지면 서로 다른 지문을 만든다")
    void bindsFingerprintToKeyMaterialAndIdentifier() {
        String oldStored = cipher.encrypt(DIARY_ID, USER_ID, "원문");
        String expected = protect(cipher, oldStored, RAW_FINGERPRINT);
        DiarySourceTextCipher sameKeyNewIdentifier = cipher("renamed", Map.of("renamed", OLD_KEY));
        DiarySourceTextCipher newKeySameIdentifier = cipher("old", Map.of("old", NEW_KEY));

        String renamedStored = sameKeyNewIdentifier.encrypt(DIARY_ID, USER_ID, "원문");
        String rekeyedStored = newKeySameIdentifier.encrypt(DIARY_ID, USER_ID, "원문");

        assertThat(protect(sameKeyNewIdentifier, renamedStored, RAW_FINGERPRINT)).isNotEqualTo(expected);
        assertThat(protect(newKeySameIdentifier, rekeyedStored, RAW_FINGERPRINT)).isNotEqualTo(expected);
    }

    @Test
    @DisplayName("활성 키가 교체되어도 저장된 암호문의 키로 기존 요청 지문을 재현한다")
    void usesEnvelopeKeyToKeepExistingRequestsStableAcrossActiveKeyRotation() {
        String oldStored = cipher.encrypt(DIARY_ID, USER_ID, "키 교체 전 일기");
        String oldProtected = protect(cipher, oldStored, RAW_FINGERPRINT);
        DiarySourceTextCipher rotated = cipher("new", Map.of("old", OLD_KEY, "new", NEW_KEY));

        assertThat(protect(rotated, oldStored, RAW_FINGERPRINT)).isEqualTo(oldProtected);
        String newStored = rotated.encrypt(DIARY_ID, USER_ID, "키 교체 후 일기");
        assertThat(protect(rotated, newStored, RAW_FINGERPRINT)).isNotEqualTo(oldProtected);
        DiarySourceTextCipher retired = cipher("new", Map.of("new", NEW_KEY));
        assertCryptoFailure(() -> protect(retired, oldStored, RAW_FINGERPRINT));
    }

    @Test
    @DisplayName("전환 이후에는 버전 0의 기존 SHA 지문으로 우회하지 않는다")
    void refusesLegacyFingerprintFallback() {
        assertCryptoFailure(() -> cipher.protectRequestFingerprint(DIARY_ID, USER_ID, "기존 원문", 0, RAW_FINGERPRINT));
    }

    @Test
    @DisplayName("원문 저장 버전과 무관하게 입력 지문은 소문자 16진수 64자리여야 한다")
    void validatesRawFingerprintForBothStorageVersions() {
        String stored = cipher.encrypt(DIARY_ID, USER_ID, "원문");
        for (String invalid : new String[]{null, "", "a".repeat(63), "a".repeat(65), "A".repeat(64),
                "z".repeat(64), " " + RAW_FINGERPRINT}) {
            assertCryptoFailure(() -> cipher.protectRequestFingerprint(DIARY_ID, USER_ID, stored, 1, invalid));
            assertCryptoFailure(() -> cipher.protectRequestFingerprint(DIARY_ID, USER_ID, "기존 원문", 0, invalid));
        }
    }

    @Test
    @DisplayName("지원하지 않는 버전이나 누락된 일기 및 사용자 식별자는 지문 보호를 거부한다")
    void rejectsUnsupportedVersionsAndMissingIdentifiers() {
        String stored = cipher.encrypt(DIARY_ID, USER_ID, "원문");

        assertCryptoFailure(() -> cipher.protectRequestFingerprint(DIARY_ID, USER_ID, stored, -1, RAW_FINGERPRINT));
        assertCryptoFailure(() -> cipher.protectRequestFingerprint(DIARY_ID, USER_ID, stored, 2, RAW_FINGERPRINT));
        assertCryptoFailure(() -> cipher.protectRequestFingerprint(null, USER_ID, stored, 1, RAW_FINGERPRINT));
        assertCryptoFailure(() -> cipher.protectRequestFingerprint(DIARY_ID, null, stored, 1, RAW_FINGERPRINT));
        assertCryptoFailure(() -> cipher.protectRequestFingerprint(null, USER_ID, "기존 원문", 0, RAW_FINGERPRINT));
    }

    @Test
    @DisplayName("키가 없거나 암호문 형식이 잘못되면 기존 해시로 대체하지 않고 지문 보호를 거부한다")
    void rejectsUnknownKeysAndMalformedEnvelopesWithoutFallback() {
        String valid = cipher.encrypt(DIARY_ID, USER_ID, "a");
        for (String malformed : new String[]{null, "기존 원문", "", ":YWJj", "old:", "old:not-base64!", "old:YWJj",
                "unknown:" + valid.substring(valid.indexOf(':') + 1),
                "bad:key:" + valid.substring(valid.indexOf(':') + 1),
                valid.substring(0, valid.length() - 1),
                "old:" + Base64.getEncoder().encodeToString(new byte[1229])}) {
            assertCryptoFailure(() -> protect(cipher, malformed, RAW_FINGERPRINT));
        }
    }

    private static String protect(DiarySourceTextCipher cipher, String storedText, String fingerprint) {
        return cipher.protectRequestFingerprint(DIARY_ID, USER_ID, storedText, 1, fingerprint);
    }

    private static DiarySourceTextCipher cipher(String activeKeyId, Map<String, String> keys) {
        return new AesGcmDiarySourceTextCipher(new DiarySourceTextEncryptionProperties(activeKeyId, keys));
    }

    private static String encodedKey(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return Base64.getEncoder().encodeToString(key);
    }

    private static void assertCryptoFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isExactlyInstanceOf(DiarySourceTextCryptoException.class)
                .hasMessage("일기 원문 암호화 처리에 실패했습니다.")
                .hasNoCause();
    }
}
