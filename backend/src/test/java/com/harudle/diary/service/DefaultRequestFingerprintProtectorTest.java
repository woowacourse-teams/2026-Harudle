package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.diary.infrastructure.crypto.DiarySourceTextCryptoException;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.support.DiarySourceTextTestSupport;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DefaultRequestFingerprintProtectorTest {

    private static final UUID USER_ID = UUID.fromString("37c77467-b38f-4323-9b58-e969cc6b31cf");
    private static final String RAW_FINGERPRINT = "a".repeat(64);
    private static final String INVALID_CLAIM_MESSAGE = "생성 요청의 일기를 확인할 수 없습니다.";

    @Mock
    private DiaryRepository diaryRepository;

    private DiarySourceTextCipher sourceTextCipher;
    private DefaultRequestFingerprintProtector protector;
    private Diary diary;

    @BeforeEach
    void setUp() {
        sourceTextCipher = spy(DiarySourceTextTestSupport.cipher());
        protector = new DefaultRequestFingerprintProtector(diaryRepository, sourceTextCipher);
        diary = Diary.create(USER_ID, LocalDate.of(2026, 10, 20), "비공개 일기 원문",
                DiarySourceTextTestSupport.cipher());
    }

    @Test
    @DisplayName("생성 실행은 저장 계약대로 지문을 보호하고 원문을 복호화하지 않는다")
    void protectEncryptedDiaryUsingThePersistenceFingerprintContract() {
        when(diaryRepository.findById(diary.getId())).thenReturn(Optional.of(diary));
        String expected = diary.protectRequestFingerprint(DiarySourceTextTestSupport.cipher(), RAW_FINGERPRINT);

        String protectedFingerprint = protector.protect(diary.getId(), USER_ID, RAW_FINGERPRINT);

        assertThat(protectedFingerprint).isEqualTo(expected).isNotEqualTo(RAW_FINGERPRINT);
        verify(sourceTextCipher, never()).decrypt(any(), any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("전환하지 않은 평문 일기의 생성 실행은 기존 SHA 지문을 허용하지 않는다")
    void rejectLegacyFingerprint() {
        ReflectionTestUtils.setField(diary, "sourceText", "기존 평문 일기");
        ReflectionTestUtils.setField(diary, "sourceTextVersion", DiarySourceTextCipher.LEGACY_VERSION);
        when(diaryRepository.findById(diary.getId())).thenReturn(Optional.of(diary));

        assertThatThrownBy(() -> protector.protect(diary.getId(), USER_ID, RAW_FINGERPRINT))
                .isInstanceOf(DiarySourceTextCryptoException.class);
        verify(sourceTextCipher, never()).decrypt(any(), any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("다른 사용자의 일기로 생성 지문을 요청하면 키를 사용하기 전에 거절한다")
    void rejectOtherOwnerBeforeProtectingFingerprint() {
        when(diaryRepository.findById(diary.getId())).thenReturn(Optional.of(diary));

        assertThatThrownBy(() -> protector.protect(diary.getId(), UUID.randomUUID(), RAW_FINGERPRINT))
                .isInstanceOf(IllegalStateException.class).hasMessage(INVALID_CLAIM_MESSAGE).hasNoCause();
        verifyNoInteractions(sourceTextCipher);
    }

    @Test
    @DisplayName("일기가 없으면 식별자나 원문을 예외에 포함하지 않고 거절한다")
    void rejectMissingDiaryWithSafeFailure() {
        when(diaryRepository.findById(diary.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> protector.protect(diary.getId(), USER_ID, RAW_FINGERPRINT))
                .isInstanceOf(IllegalStateException.class).hasMessage(INVALID_CLAIM_MESSAGE).hasNoCause();
        verifyNoInteractions(sourceTextCipher);
    }

    @Test
    @DisplayName("일기나 사용자 식별자가 없으면 저장소와 키를 사용하지 않고 거절한다")
    void rejectMissingIdentifiersBeforeReadingDiary() {
        assertThatThrownBy(() -> protector.protect(null, USER_ID, RAW_FINGERPRINT))
                .isInstanceOf(IllegalStateException.class).hasMessage(INVALID_CLAIM_MESSAGE).hasNoCause();
        assertThatThrownBy(() -> protector.protect(diary.getId(), null, RAW_FINGERPRINT))
                .isInstanceOf(IllegalStateException.class).hasMessage(INVALID_CLAIM_MESSAGE).hasNoCause();
        verifyNoInteractions(diaryRepository, sourceTextCipher);
    }
}
