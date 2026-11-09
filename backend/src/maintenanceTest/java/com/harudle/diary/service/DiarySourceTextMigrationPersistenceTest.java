package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.diary.infrastructure.migration.DiarySourceTextMigration;
import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;
import com.harudle.diary.service.dto.DiaryDetailResult;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.service.DiaryGenerationExecutor;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class DiarySourceTextMigrationPersistenceTest extends DiarySourceTextEncryptionPersistenceSupport {

    @Test
    @DisplayName("평문에서 전환한 대기 작업도 실제 생성에 성공하고 동일 요청으로 재조회한다")
    void completeRealCreationAfterMigratingPending() throws SQLException {
        assertRealCreationWithEncryptedSourceAndProtectedFingerprint((command, created) -> {
            String rawFingerprint = fingerprintGenerator.generate(new GenerateDiaryImageCommand(
                    USER_ID, created.id(), DIARY_DATE, SOURCE_TEXT, command.idempotencyKey()
            ));
            // Recreate an already accepted v0 queue entry before running the real conversion tool.
            transactionTemplate.executeWithoutResult(status -> {
                createQuery("UPDATE diaries SET source_text = ?, source_text_version = 0 WHERE id = ?",
                        SOURCE_TEXT, created.id()).executeUpdate();
                createQuery("UPDATE diary_generations SET request_fingerprint = ? WHERE id = ?",
                        rawFingerprint, created.generation().id()).executeUpdate();
            });
            Object[] before = (Object[]) readValue(
                    "SELECT status, created_at, usage_date FROM diary_generations WHERE id = ?",
                    created.generation().id()
            );
            try (Connection connection = dataSource.getConnection()) {
                var migrated = new DiarySourceTextMigration(connection, sourceTextCipher, fingerprintGenerator)
                        .migrate(10, 1);
                assertThat(migrated.migrated()).isEqualTo(1);
                assertThat(migrated.skippedProcessing()).isZero();
                assertThat(migrated.failed()).isZero();
            }
            assertThat((Object[]) readValue(
                    "SELECT status, created_at, usage_date FROM diary_generations WHERE id = ?",
                    created.generation().id()
            )).containsExactly(before);

        });
    }

    @Test
    @DisplayName("실제 평문 전환 후 상세와 회원 재요청은 원문 및 기존 성공 결과를 복원하고 AI를 다시 호출하지 않는다")
    void replayExistingSuccessfulGenerationAfterMigratingLegacySource() throws SQLException {
        DiaryGenerationExecutor executor = mock(DiaryGenerationExecutor.class);
        when(executor.isConfigured()).thenReturn(true);
        DiaryCreationService creationService = new DiaryCreationService(transactionService, executor);
        CreateDiaryCommand command = createCommand("전환 전에 이미 완성한 그림일기 😀");
        UUID diaryId = insertLegacyDiary(command);
        UUID generationId = (UUID) readValue("SELECT id FROM diary_generations WHERE diary_id = ?", diaryId);
        String canonicalSha = fingerprintGenerator.generate(new GenerateDiaryImageCommand(
                USER_ID, diaryId, DIARY_DATE, command.sourceText(), command.idempotencyKey()
        ));
        assertThat(findStoredSource(diaryId)).isEqualTo(new StoredSource(command.sourceText(), 0));
        assertThat(readValue("SELECT request_fingerprint FROM diary_generations WHERE id = ?", generationId))
                .isEqualTo(canonicalSha);

        try (Connection connection = dataSource.getConnection()) {
            var migrated = new DiarySourceTextMigration(connection, sourceTextCipher, fingerprintGenerator)
                    .migrate(10, 1);
            assertThat(migrated.migrated()).isEqualTo(1);
            assertThat(migrated.failed()).isZero();
        }
        StoredSource stored = findStoredSource(diaryId);
        DiaryDetailResult detail = queryService.getDetail(USER_ID, diaryId);
        CreateDiaryResult replayed = creationService.create(command);

        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.text()).isNotEqualTo(command.sourceText());
        assertThat(readValue("SELECT request_fingerprint FROM diary_generations WHERE id = ?", generationId))
                .isNotEqualTo(canonicalSha)
                .isEqualTo(sourceTextCipher.protectRequestFingerprint(
                        diaryId, USER_ID, stored.text(), stored.version(), canonicalSha
                ));
        assertThat(detail.sourceText()).isEqualTo(command.sourceText());
        assertThat(detail.generation().id()).isEqualTo(generationId);
        assertThat(replayed.id()).isEqualTo(diaryId);
        assertThat(replayed.sourceText()).isEqualTo(command.sourceText());
        assertThat(replayed.newlyCreated()).isFalse();
        assertThat(replayed.generation()).isEqualTo(detail.generation());
        assertThat(replayed.generation().status()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(replayed.generation().title()).isEqualTo("기존 그림일기");
        assertThat(replayed.generation().imageObjectKey()).isEqualTo("generated/%s.png".formatted(diaryId));
        assertThat(replayed.usage().usedCount()).isZero();
        assertThat(countUserDiaries()).isEqualTo(1);
        assertThat(readValue("SELECT COUNT(*) FROM diary_generations WHERE diary_id = ?", diaryId)).isEqualTo(1L);
        assertThat(findStoredSource(diaryId)).isEqualTo(stored);
        verify(executor, never()).generate(any(GenerateDiaryImageCommand.class), any(UUID.class));
    }
}
