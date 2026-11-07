package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.diary.infrastructure.crypto.DiarySourceTextCryptoException;
import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.DiaryDetailResult;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.diary.service.exception.DiarySourceTextWriteException;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class DiarySourceTextEncryptionPersistenceTest extends DiarySourceTextEncryptionPersistenceSupport {

    @ParameterizedTest
    @ValueSource(strings = {"ENCRYPT", "FINGERPRINT"})
    @DisplayName("신규 원문 보호 실패는 일기와 생성 기록 및 사용량을 남기지 않는다")
    void rollBackNewClaimWhenSourceProtectionFails(String failurePoint) {
        DiarySourceTextCipher failingCipher = mock(DiarySourceTextCipher.class);
        when(failingCipher.encrypt(any(UUID.class), any(UUID.class), any(String.class)))
                .thenAnswer(invocation -> {
                    if ("ENCRYPT".equals(failurePoint)) {
                        throw new DiarySourceTextCryptoException();
                    }
                    return sourceTextCipher.encrypt(invocation.getArgument(0), invocation.getArgument(1),
                            invocation.getArgument(2));
                });
        if ("FINGERPRINT".equals(failurePoint)) {
            when(failingCipher.protectRequestFingerprint(any(UUID.class), any(UUID.class), any(String.class),
                    eq(1), any(String.class)))
                    .thenAnswer(invocation -> {
                        entityManager.flush();
                        assertThat(countUserDiaries()).isEqualTo(1);
                        throw new DiarySourceTextCryptoException();
                    });
        }
        DiaryCreationClaimService failingClaims = new DiaryCreationClaimService(
                diaryRepository, promptRepository, generationRepository, completionService, fingerprintGenerator,
                failingCipher, new DiarySourceTextWriteProperties(true), serviceClock, lifecycleProperties,
                admissionLock, workerProperties, queueMetrics
        );
        MemberDiaryCreationTransactionService failingTransactions =
                new MemberDiaryCreationTransactionService(failingClaims, usageService);

        assertThatThrownBy(() -> transactionTemplate.execute(status ->
                failingTransactions.enqueue(createCommand(SOURCE_TEXT), true)))
                .isInstanceOf(DiarySourceTextWriteException.class)
                .hasNoCause();

        assertThat(countUserDiaries()).isZero();
        assertThat(readValue("SELECT COUNT(*) FROM diary_generations g JOIN diaries d ON d.id = g.diary_id "
                + "WHERE d.user_id = ?", USER_ID)).isEqualTo(0L);
        assertThat(readValue("SELECT COUNT(*) FROM daily_generation_usage WHERE user_id = ?", USER_ID))
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("회원 생성은 원문을 v1 암호문으로 저장하고 상세와 멱등 재요청에는 원문을 반환한다")
    void persistEncryptedSourceAndReturnPlaintextOnlyWhenReading() {
        CreateDiaryCommand command = createCommand(SOURCE_TEXT);

        MemberDiaryCreationClaim created = transactionService.claim(command, true);
        StoredSource stored = findStoredSource(created.claim().diaryId());
        markGenerationSucceeded(created.claim());
        DiaryDetailResult detail = queryService.getDetail(USER_ID, created.claim().diaryId());
        MemberDiaryCreationClaim replayed = transactionService.claim(command, true);

        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.text()).isNotEqualTo(SOURCE_TEXT).doesNotContain(SOURCE_TEXT);
        String rawFingerprint = fingerprintGenerator.generate(new GenerateDiaryImageCommand(
                USER_ID, created.claim().diaryId(), DIARY_DATE, SOURCE_TEXT, command.idempotencyKey()
        ));
        assertThat(readValue("SELECT request_fingerprint FROM diary_generations WHERE id = ?",
                created.claim().generationId()))
                .isNotEqualTo(rawFingerprint)
                .isEqualTo(sourceTextCipher.protectRequestFingerprint(
                        created.claim().diaryId(), USER_ID, stored.text(), 1, rawFingerprint
                ));
        assertThat(created.claim().sourceText()).isEqualTo(SOURCE_TEXT);
        assertThat(detail.sourceText()).isEqualTo(SOURCE_TEXT);
        assertThat(replayed.claim().sourceText()).isEqualTo(SOURCE_TEXT);
        assertThat(replayed.claim().diaryId()).isEqualTo(created.claim().diaryId());
        assertThat(replayed.claim().newlyCreated()).isFalse();
        assertThat(replayed.usage().usedCount()).isEqualTo(1);
        assertThat(findStoredSource(created.claim().diaryId())).isEqualTo(stored);
        assertThat(countUserDiaries()).isEqualTo(1);
    }

    @Test
    @DisplayName("신규 암호문 대기 작업은 처리기가 원문으로 실행하고 같은 요청으로 재조회한다")
    void completeRealCreationWithEncryptedSourceAndProtectedFingerprint() throws SQLException {
        assertRealCreationWithEncryptedSourceAndProtectedFingerprint((command, created) -> { });
    }

    @Test
    @DisplayName("공통 생성 선점도 300 코드 포인트의 원문을 컬럼 길이 제한 없이 암호화한다")
    void persistMaximumLengthSourceThroughCommonClaim() {
        String maximumLengthSource = "하".repeat(299) + "😀";
        CreateDiaryCommand command = createCommand(maximumLengthSource);

        DiaryCreationClaim created = transactionTemplate.execute(status -> claimService.claim(command, true));

        assertThat(created).isNotNull();
        StoredSource stored = findStoredSource(created.diaryId());
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.text().length()).isGreaterThan(300);
        assertThat(created.sourceText()).isEqualTo(maximumLengthSource);
        assertThat(queryService.getDetail(USER_ID, created.diaryId()).sourceText())
                .isEqualTo(maximumLengthSource);
        assertThat(readValue("SELECT COUNT(*) FROM daily_generation_usage WHERE user_id = ?", USER_ID))
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("기존 v0 평문이 남으면 상세와 재요청을 거절하면서 v1 일기는 계속 조회한다")
    void rejectLegacyPlaintextWhileKeepingEncryptedDiaryReadable() {
        CreateDiaryCommand legacyCommand = createCommand("기존에 작성한 일기");
        UUID legacyDiaryId = insertLegacyDiary(legacyCommand);
        DiaryCreationClaim encrypted = transactionService.claim(createCommand(SOURCE_TEXT), true).claim();
        markGenerationSucceeded(encrypted);

        assertThatThrownBy(() -> transactionService.claim(legacyCommand, true))
                .isInstanceOf(DiarySourceTextCryptoException.class);
        assertThatThrownBy(() -> queryService.getDetail(USER_ID, legacyDiaryId))
                .isInstanceOf(DiarySourceTextCryptoException.class);
        assertThat(queryService.getDetail(USER_ID, encrypted.diaryId()).sourceText()).isEqualTo(SOURCE_TEXT);
        assertThat(findStoredSource(legacyDiaryId))
                .isEqualTo(new StoredSource(legacyCommand.sourceText(), 0));
        assertThat(findStoredSource(encrypted.diaryId()).version()).isEqualTo(1);
        assertThat(queryService.getTimeline(USER_ID, 2026, 8).days().stream()
                .flatMap(day -> day.items().stream()))
                .extracting(item -> item.id())
                .containsExactlyInAnyOrder(legacyDiaryId, encrypted.diaryId());
    }

    @Test
    @DisplayName("암호문이 변조된 타인 일기도 복호화 전에 소유권을 검사하고 본인 조회는 실패한다")
    void checkOwnershipBeforeDecryptingTamperedCiphertext() {
        DiaryCreationClaim created = transactionService.claim(createCommand(SOURCE_TEXT), true).claim();
        replaceWithCiphertextFromAnotherDiary(created.diaryId());

        assertThatThrownBy(() -> queryService.getDetail(OTHER_USER_ID, created.diaryId()))
                .isInstanceOf(DiaryAccessDeniedException.class);
        assertThatThrownBy(() -> queryService.getDetail(USER_ID, created.diaryId()))
                .isInstanceOf(DiarySourceTextCryptoException.class);
    }

    @Test
    @DisplayName("재요청 중 복호화 실패는 생성 중단과 사용량 복구 및 일기 삭제를 모두 롤백한다")
    void rollbackReplaySideEffectsWhenCiphertextCannotBeDecrypted() {
        CreateDiaryCommand command = createCommand(SOURCE_TEXT);
        DiaryCreationClaim created = transactionService.claim(command, true).claim();
        replaceWithCiphertextFromAnotherDiary(created.diaryId());
        StoredSource tampered = findStoredSource(created.diaryId());
        executeUpdate("UPDATE diary_generations SET updated_at = ? WHERE id = ?",
                serviceClock.instant().minusSeconds(86_400), created.generationId());

        assertThatThrownBy(() -> transactionService.claim(command, true))
                .isInstanceOf(DiarySourceTextCryptoException.class);

        assertThat(readValue("SELECT status FROM diary_generations WHERE id = ?", created.generationId()))
                .isEqualTo("PROCESSING");
        assertThat(readValue("SELECT completed_at FROM diary_generations WHERE id = ?", created.generationId()))
                .isNull();
        assertThat(readValue("SELECT deleted_at FROM diaries WHERE id = ?", created.diaryId())).isNull();
        assertThat(readValue("SELECT used_count FROM daily_generation_usage WHERE user_id = ?", USER_ID))
                .isEqualTo(1);
        assertThat(findStoredSource(created.diaryId())).isEqualTo(tampered);
        assertThat(countUserDiaries()).isEqualTo(1);
    }

    @Test
    @DisplayName("월간 목록과 스트릭 및 공개 공유 조회는 손상된 원문을 복호화하지 않는다")
    void readSummariesAndPublicShareWithoutDecryptingSource() {
        LocalDate today = LocalDate.now(serviceClock);
        CreateDiaryCommand command = new CreateDiaryCommand(USER_ID, today, SOURCE_TEXT, UUID.randomUUID());
        DiaryCreationClaim created = transactionService.claim(command, true).claim();
        markGenerationSucceeded(created);
        replaceWithCiphertextFromAnotherDiary(created.diaryId());
        UUID shareId = UUID.randomUUID();
        executeUpdate("INSERT INTO share_links (id, generation_id) VALUES (?, ?)",
                shareId, created.generationId());

        assertThat(queryService.getTimeline(USER_ID, today.getYear(), today.getMonthValue()).days().stream()
                .flatMap(day -> day.items().stream()))
                .extracting(item -> item.id())
                .containsExactly(created.diaryId());
        assertThat(queryService.getCurrentStreak(USER_ID).streakCount()).isEqualTo(1);
        assertThat(shareQueryService.getPublicShare(shareId, null).title()).isEqualTo("오늘의 그림일기");
    }

}
