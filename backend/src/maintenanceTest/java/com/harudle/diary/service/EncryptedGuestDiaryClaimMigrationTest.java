package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.auth.domain.User;
import com.harudle.diary.infrastructure.migration.DiarySourceTextMigration;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class EncryptedGuestDiaryClaimMigrationTest extends EncryptedGuestDiaryClaimPersistenceSupport {

    @Test
    @DisplayName("회원에게 가져간 암호화 일기는 읽기 전용 전환 검증에 성공하고 저장값을 보존한다")
    void verifyTransferredDiaryWithoutChangingStoredValues() throws SQLException {
        Fixture guest = fixture(1);
        User member = user("전환 검증 회원");
        claimService.claim(member.getId(), guest.token());

        Map<String, Object> afterClaim = persistedState(guest.diaryId());
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            DiarySourceTextMigration.VerificationResult verified =
                    new DiarySourceTextMigration(connection, cipher, fingerprints).verify(100);

            assertThat(verified.checked()).isPositive();
            assertThat(verified.failed()).isZero();
        }
        assertThat(persistedState(guest.diaryId())).isEqualTo(afterClaim);
    }
}
