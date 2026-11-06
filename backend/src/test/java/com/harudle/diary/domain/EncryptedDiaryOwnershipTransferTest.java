package com.harudle.diary.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.diary.infrastructure.crypto.DiarySourceTextCryptoException;
import com.harudle.support.DiarySourceTextTestSupport;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EncryptedDiaryOwnershipTransferTest {

    private static final UUID GUEST = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final String TEXT = "회원에게 가져갈 체험 일기 🙂";
    private final DiarySourceTextCipher cipher = DiarySourceTextTestSupport.cipher();

    @Test
    @DisplayName("암호화된 체험 일기는 새 작성자로 다시 암호화하고 예전 작성자로는 읽을 수 없다")
    void reencryptWithNewOwnerAuthentication() {
        Diary diary = diary();
        String original = storedText(diary);

        diary.transferOwnership(GUEST, MEMBER, cipher);

        String transferred = storedText(diary);
        assertThat(diary.getUserId()).isEqualTo(MEMBER);
        assertThat(transferred).isNotEqualTo(original).doesNotContain(TEXT);
        assertThat(diary.decryptSourceText(cipher)).isEqualTo(TEXT);
        assertThatThrownBy(() -> cipher.decrypt(diary.getId(), GUEST, transferred, 1))
                .isExactlyInstanceOf(DiarySourceTextCryptoException.class);
        assertThatThrownBy(() -> cipher.decrypt(diary.getId(), MEMBER, original, 1))
                .isExactlyInstanceOf(DiarySourceTextCryptoException.class);
    }

    @Test
    @DisplayName("기존 평문 일기를 가져가도 평문 버전을 유지한다")
    void preserveLegacyStorageCompatibility() {
        Diary diary = diary();
        ReflectionTestUtils.setField(diary, "sourceText", TEXT);
        ReflectionTestUtils.setField(diary, "sourceTextVersion", 0);
        DiarySourceTextCipher unused = mock(DiarySourceTextCipher.class);

        diary.transferOwnership(GUEST, MEMBER, unused);

        assertThat(storedText(diary)).isEqualTo(TEXT);
        assertThat(ReflectionTestUtils.getField(diary, "sourceTextVersion")).isEqualTo(0);
        assertThat(diary.decryptSourceText(cipher)).isEqualTo(TEXT);
        verifyNoInteractions(unused);
    }

    @Test
    @DisplayName("현재 소유자가 아니면 복호화를 시도하기 전에 거절한다")
    void authorizeBeforeDecryption() {
        Diary diary = diary();
        DiarySourceTextCipher unused = mock(DiarySourceTextCipher.class);

        assertThatThrownBy(() -> diary.transferOwnership(UUID.randomUUID(), MEMBER, unused))
                .isExactlyInstanceOf(IllegalStateException.class);

        assertThat(diary.getUserId()).isEqualTo(GUEST);
        verifyNoInteractions(unused);
    }

    @Test
    @DisplayName("기존 암호문 복호화가 실패하면 작성자와 저장값을 바꾸지 않는다")
    void retainOriginalStateWhenDecryptionFails() {
        Diary diary = diary();
        String original = storedText(diary);
        DiarySourceTextCipher failing = mock(DiarySourceTextCipher.class);
        when(failing.decrypt(diary.getId(), GUEST, original, 1))
                .thenThrow(new DiarySourceTextCryptoException());

        assertThatThrownBy(() -> diary.transferOwnership(GUEST, MEMBER, failing))
                .isExactlyInstanceOf(DiarySourceTextCryptoException.class);

        assertThat(diary.getUserId()).isEqualTo(GUEST);
        assertThat(storedText(diary)).isEqualTo(original);
        assertThat(diary.decryptSourceText(cipher)).isEqualTo(TEXT);
    }

    @Test
    @DisplayName("새 작성자로 암호화가 실패해도 작성자와 저장값을 바꾸지 않는다")
    void retainOriginalStateWhenEncryptionFails() {
        Diary diary = diary();
        String original = storedText(diary);
        DiarySourceTextCipher failing = mock(DiarySourceTextCipher.class);
        when(failing.decrypt(diary.getId(), GUEST, original, 1)).thenReturn(TEXT);
        when(failing.encrypt(diary.getId(), MEMBER, TEXT)).thenThrow(new DiarySourceTextCryptoException());

        assertThatThrownBy(() -> diary.transferOwnership(GUEST, MEMBER, failing))
                .isExactlyInstanceOf(DiarySourceTextCryptoException.class);

        assertThat(diary.getUserId()).isEqualTo(GUEST);
        assertThat(storedText(diary)).isEqualTo(original);
        assertThat(diary.decryptSourceText(cipher)).isEqualTo(TEXT);
    }

    private Diary diary() {
        return Diary.create(GUEST, LocalDate.of(2026, 11, 6), TEXT, cipher);
    }

    private String storedText(Diary diary) {
        return (String) ReflectionTestUtils.getField(diary, "sourceText");
    }
}
