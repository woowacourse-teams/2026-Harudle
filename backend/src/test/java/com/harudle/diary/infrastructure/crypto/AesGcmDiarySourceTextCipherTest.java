package com.harudle.diary.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.diary.domain.DiarySourceTextCipher;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AesGcmDiarySourceTextCipherTest {

    private static final UUID DIARY_ID = UUID.fromString("f600df8a-7306-48c9-a3b5-c6a62efc4484");
    private static final UUID USER_ID = UUID.fromString("e053fe37-0b12-47a9-9ea7-7af861f79f36");
    private static final String OLD_KEY = encodedKey((byte) 1);
    private static final String NEW_KEY = encodedKey((byte) 2);
    private static final String FAILURE_MESSAGE = "일기 원문 암호화 처리에 실패했습니다.";

    private final DiarySourceTextCipher cipher = cipher("old", Map.of("old", OLD_KEY));

    @Test
    @DisplayName("암호화 설정이 없으면 생성을 거부한다")
    void requiresEncryptionConfiguration() {
        assertCryptoFailure(() -> new AesGcmDiarySourceTextCipher(null));
    }

    @Test
    @DisplayName("한글 원문을 앞뒤 공백 제거 후 암호화하고 복호화한다")
    void roundTripsKoreanTextWithNormalizedWhitespace() {
        String stored = cipher.encrypt(DIARY_ID, USER_ID, "  오늘 친구와 카페에 갔다. 🙂\n즐거웠다.  ");

        assertThat(stored).startsWith("old:").doesNotContain("오늘", "카페", "즐거웠다");
        assertThat(cipher.decrypt(DIARY_ID, USER_ID, stored, 1)).isEqualTo("오늘 친구와 카페에 갔다. 🙂\n즐거웠다.");
    }

    @Test
    @DisplayName("4바이트 이모지 300자로 된 최대 길이 원문을 암복호화한다")
    void roundTripsThreeHundredFourByteCodePoints() {
        String plaintext = "🙂".repeat(300);

        String stored = cipher.encrypt(DIARY_ID, USER_ID, plaintext);

        assertThat(payload(stored)).hasSize(12 + 1200 + 16);
        assertThat(cipher.decrypt(DIARY_ID, USER_ID, stored, 1)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("동일한 원문을 반복 암호화해도 새로운 논스를 사용한다")
    void createsFreshNonceForRepeatedEncryption() {
        String first = cipher.encrypt(DIARY_ID, USER_ID, "동일한 원문");
        String second = cipher.encrypt(DIARY_ID, USER_ID, "동일한 원문");

        assertThat(first).isNotEqualTo(second);
        assertThat(java.util.Arrays.copyOf(payload(first), 12))
                .isNotEqualTo(java.util.Arrays.copyOf(payload(second), 12));
        assertThat(cipher.decrypt(DIARY_ID, USER_ID, first, 1)).isEqualTo("동일한 원문");
        assertThat(cipher.decrypt(DIARY_ID, USER_ID, second, 1)).isEqualTo("동일한 원문");
    }

    @Test
    @DisplayName("활성 키로 새 원문을 암호화하고 보관한 이전 키로 기존 원문을 읽는다")
    void writesWithActiveKeyAndReadsRetainedOldKey() {
        String oldStored = cipher.encrypt(DIARY_ID, USER_ID, "교체 전 일기");
        DiarySourceTextCipher rotated = cipher("new", Map.of("old", OLD_KEY, "new", NEW_KEY));

        String newStored = rotated.encrypt(DIARY_ID, USER_ID, "교체 후 일기");

        assertThat(newStored).startsWith("new:");
        assertThat(rotated.decrypt(DIARY_ID, USER_ID, oldStored, 1)).isEqualTo("교체 전 일기");
        assertThat(rotated.decrypt(DIARY_ID, USER_ID, newStored, 1)).isEqualTo("교체 후 일기");
        assertCryptoFailure(() -> cipher.decrypt(DIARY_ID, USER_ID, newStored, 1));
        DiarySourceTextCipher retired = cipher("new", Map.of("new", NEW_KEY));
        assertCryptoFailure(() -> retired.decrypt(DIARY_ID, USER_ID, oldStored, 1));
    }

    @Test
    @DisplayName("논스와 암호문 및 인증 태그의 변조를 거부한다")
    void rejectsNonceCiphertextAndTagTampering() {
        String stored = cipher.encrypt(DIARY_ID, USER_ID, "변조 방지 원문");
        for (int offset : new int[]{0, 12, payload(stored).length - 1}) {
            byte[] tampered = payload(stored);
            tampered[offset] ^= 1;
            assertCryptoFailure(() -> cipher.decrypt(DIARY_ID, USER_ID, envelope("old", tampered), 1));
        }
    }

    @Test
    @DisplayName("암호문을 다른 일기나 사용자에게 옮기면 복호화를 거부한다")
    void rejectsMovingCiphertextToAnotherDiaryOrUser() {
        String stored = cipher.encrypt(DIARY_ID, USER_ID, "다른 행으로 옮기면 읽을 수 없다");

        assertCryptoFailure(() -> cipher.decrypt(UUID.randomUUID(), USER_ID, stored, 1));
        assertCryptoFailure(() -> cipher.decrypt(DIARY_ID, UUID.randomUUID(), stored, 1));
    }

    @Test
    @DisplayName("암호화 목적과 버전 및 키 식별자를 추가 인증 데이터로 검증한다")
    void authenticatesPurposeVersionAndKeyIdentifier() throws GeneralSecurityException {
        byte[] plaintext = "원문".getBytes(StandardCharsets.UTF_8);
        for (String aad : new String[]{
                "other.purpose:v1:old:" + DIARY_ID + ":" + USER_ID,
                "harudle.diary.source-text:v2:old:" + DIARY_ID + ":" + USER_ID,
                "harudle.diary.source-text:v1:changed:" + DIARY_ID + ":" + USER_ID
        }) {
            String stored = encryptExternally(plaintext, aad);
            assertCryptoFailure(() -> cipher.decrypt(DIARY_ID, USER_ID, stored, 1));
        }
    }

    @Test
    @DisplayName("암호문 형식이 잘못되면 평문으로 대체하지 않고 복호화를 거부한다")
    void rejectsMalformedEncryptedPayloadWithoutPlaintextFallback() {
        String valid = cipher.encrypt(DIARY_ID, USER_ID, "a");
        for (String malformed : new String[]{
                "평문 원문", "", ":YWJj", "old:", "old:not-base64!", "old:YWJj",
                "unknown:" + valid.substring(valid.indexOf(':') + 1),
                "bad:key:" + valid.substring(valid.indexOf(':') + 1),
                valid.substring(0, valid.length() - 1),
                "old:" + Base64.getEncoder().encodeToString(new byte[1229])
        }) {
            assertCryptoFailure(() -> cipher.decrypt(DIARY_ID, USER_ID, malformed, 1));
        }
        assertCryptoFailure(() -> cipher.decrypt(DIARY_ID, USER_ID, null, 1));
    }

    @Test
    @DisplayName("알 수 없는 원문 저장 버전은 복호화를 거부한다")
    void rejectsUnknownVersions() {
        String stored = cipher.encrypt(DIARY_ID, USER_ID, "원문");

        assertCryptoFailure(() -> cipher.decrypt(DIARY_ID, USER_ID, stored, -1));
        assertCryptoFailure(() -> cipher.decrypt(DIARY_ID, USER_ID, stored, 2));
    }

    @Test
    @DisplayName("전환 이후에는 유효한 원문이라도 버전 0의 복호화를 거절한다")
    void refusesLegacyPlaintextEvenWhenItMeetsDiaryValidationRules() {
        assertCryptoFailure(() -> cipher.decrypt(DIARY_ID, USER_ID, "  기존 원문 🙂  ", 0));
        assertCryptoFailure(() -> cipher.decrypt(DIARY_ID, USER_ID, "🙂".repeat(300), 0));
        assertCryptoFailure(() -> cipher.decrypt(DIARY_ID, USER_ID, "기존 원문", 1));
    }

    @Test
    @DisplayName("신규 암호화와 기존 평문 조회 모두 잘못된 원문을 거부한다")
    void rejectsInvalidPlaintextForBothNewAndLegacyValues() {
        for (String invalid : new String[]{null, "", "  ", "🙂".repeat(301), "invalid\uD800"}) {
            assertCryptoFailure(() -> cipher.encrypt(DIARY_ID, USER_ID, invalid));
            assertCryptoFailure(() -> cipher.decrypt(DIARY_ID, USER_ID, invalid, 0));
        }
    }

    @Test
    @DisplayName("인증 태그가 유효해도 잘못된 UTF-8이나 일기 원문 규칙 위반은 거부한다")
    void rejectsAuthenticatedInvalidUtf8AndInvalidDiaryText() throws GeneralSecurityException {
        for (byte[] invalid : new byte[][]{
                {(byte) 0xC3, (byte) 0x28},
                " ".getBytes(StandardCharsets.UTF_8),
                "a".repeat(301).getBytes(StandardCharsets.UTF_8)
        }) {
            String stored = encryptExternally(invalid, expectedAad());
            assertCryptoFailure(() -> cipher.decrypt(DIARY_ID, USER_ID, stored, 1));
        }
    }

    @Test
    @DisplayName("암복호화하려면 일기와 사용자 식별자가 있어야 한다")
    void requiresDiaryAndUserIdentifiers() {
        String stored = cipher.encrypt(DIARY_ID, USER_ID, "원문");

        assertCryptoFailure(() -> cipher.encrypt(null, USER_ID, "원문"));
        assertCryptoFailure(() -> cipher.encrypt(DIARY_ID, null, "원문"));
        assertCryptoFailure(() -> cipher.decrypt(null, USER_ID, stored, 1));
        assertCryptoFailure(() -> cipher.decrypt(DIARY_ID, null, stored, 1));
        assertCryptoFailure(() -> cipher.decrypt(null, USER_ID, "기존 원문", 0));
    }

    private static void assertCryptoFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isExactlyInstanceOf(DiarySourceTextCryptoException.class)
                .hasMessage(FAILURE_MESSAGE)
                .hasNoCause();
    }

    private static DiarySourceTextCipher cipher(String activeKeyId, Map<String, String> keys) {
        return new AesGcmDiarySourceTextCipher(new DiarySourceTextEncryptionProperties(activeKeyId, keys));
    }

    private static String encodedKey(byte value) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, value);
        return Base64.getEncoder().encodeToString(key);
    }

    private static byte[] payload(String stored) {
        return Base64.getDecoder().decode(stored.substring(stored.indexOf(':') + 1));
    }

    private static String envelope(String keyId, byte[] payload) {
        return keyId + ":" + Base64.getEncoder().encodeToString(payload);
    }

    private static String expectedAad() {
        return "harudle.diary.source-text:v1:old:" + DIARY_ID + ":" + USER_ID;
    }

    private static String encryptExternally(byte[] plaintext, String aad) throws GeneralSecurityException {
        byte[] nonce = new byte[12];
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(OLD_KEY), "AES"),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(plaintext);
        byte[] payload = ByteBuffer.allocate(nonce.length + ciphertext.length)
                .put(nonce)
                .put(ciphertext)
                .array();
        return envelope("old", payload);
    }
}
