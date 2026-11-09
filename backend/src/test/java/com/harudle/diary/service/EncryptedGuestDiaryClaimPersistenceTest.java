package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.auth.domain.User;
import com.harudle.diary.infrastructure.crypto.DiarySourceTextCryptoException;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class EncryptedGuestDiaryClaimPersistenceTest extends EncryptedGuestDiaryClaimPersistenceSupport {

    @Test
    @DisplayName("회원이 가져간 암호화 일기는 상세와 같은 요청 재조회에 성공한다")
    void decryptAndReplayAfterEncryptedClaim() {
        Fixture guest = fixture(1);
        User member = user("회원");
        String original = storedText(guest.diaryId());

        var first = claimService.claim(member.getId(), guest.token());
        var second = claimService.claim(member.getId(), guest.token());

        assertThat(second).isEqualTo(first);
        assertThat(storedText(guest.diaryId())).isNotEqualTo(original).doesNotContain(TEXT);
        assertThat(queryService.getDetail(member.getId(), guest.diaryId()).sourceText()).isEqualTo(TEXT);
        assertThatThrownBy(() -> queryService.getDetail(guest.userId(), guest.diaryId()))
                .isExactlyInstanceOf(DiaryAccessDeniedException.class);
        assertThatThrownBy(() -> cipher.decrypt(guest.diaryId(), guest.userId(), storedText(guest.diaryId()), 1))
                .isExactlyInstanceOf(DiarySourceTextCryptoException.class);
        assertReplay(member.getId(), guest);

    }

    @Test
    @DisplayName("전환 전 평문 체험 일기는 거절하고 소유자와 지문과 세션을 유지한다")
    void rejectLegacyClaimWithoutChangingOwnerOrSession() {
        Fixture guest = fixture(0);
        User member = user("기존 평문 회원");
        Map<String, Object> before = persistedState(guest.diaryId());

        assertThatThrownBy(() -> claimService.claim(member.getId(), guest.token()))
                .isExactlyInstanceOf(DiarySourceTextCryptoException.class);

        assertThat(persistedState(guest.diaryId())).isEqualTo(before);
        assertThat(session(guest).isClaimed()).isFalse();
    }

    @Test
    @DisplayName("가져가기 저장 후 트랜잭션이 실패하면 소유자와 암호문과 지문과 세션이 모두 복원된다")
    void rollbackAllClaimChangesTogether() {
        Fixture guest = fixture(1);
        User member = user("롤백 회원");
        Map<String, Object> before = persistedState(guest.diaryId());

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            claimService.claim(member.getId(), guest.token());
            assertThat(persistedState(guest.diaryId()).get("user_id")).isEqualTo(member.getId());
            throw new IllegalStateException("가져가기 이후 처리 실패");
        })).isExactlyInstanceOf(IllegalStateException.class);

        assertThat(persistedState(guest.diaryId())).isEqualTo(before);
        assertThat(session(guest).isClaimed()).isFalse();
        assertThat(queryService.getDetail(guest.userId(), guest.diaryId()).sourceText()).isEqualTo(TEXT);
    }

    @Test
    @DisplayName("기존 암호문 인증 실패 시 소유권과 세션을 이전하지 않는다")
    void rejectTamperedCiphertextWithoutClaimingSession() {
        Fixture guest = fixture(1);
        User member = user("변조 거절 회원");
        String encryptedForAnotherDiary = cipher.encrypt(UUID.randomUUID(), guest.userId(), TEXT);
        jdbc.update("UPDATE diaries SET source_text = ? WHERE id = ?", encryptedForAnotherDiary, guest.diaryId());
        Map<String, Object> before = persistedState(guest.diaryId());

        assertThatThrownBy(() -> claimService.claim(member.getId(), guest.token()))
                .isExactlyInstanceOf(DiarySourceTextCryptoException.class);

        assertThat(persistedState(guest.diaryId())).isEqualTo(before);
        assertThat(session(guest).isClaimed()).isFalse();
    }

}
