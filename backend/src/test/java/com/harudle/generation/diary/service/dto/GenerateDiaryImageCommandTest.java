package com.harudle.generation.diary.service.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GenerateDiaryImageCommandTest {

    @Test
    @DisplayName("그림일기 생성 명령의 일기 내용 앞뒤 공백을 제거한다")
    void createGenerateDiaryImageCommand() {
        UUID userId = UUID.randomUUID();
        UUID diaryId = UUID.randomUUID();
        LocalDate diaryDate = LocalDate.of(2026, 8, 10);
        UUID idempotencyKey = UUID.randomUUID();

        GenerateDiaryImageCommand command = new GenerateDiaryImageCommand(
                userId,
                diaryId,
                diaryDate,
                " 오늘 친구와 카페에 갔다. ",
                idempotencyKey
        );

        assertThat(command.userId()).isEqualTo(userId);
        assertThat(command.diaryId()).isEqualTo(diaryId);
        assertThat(command.diaryDate()).isEqualTo(diaryDate);
        assertThat(command.diaryText()).isEqualTo("오늘 친구와 카페에 갔다.");
        assertThat(command.idempotencyKey()).isEqualTo(idempotencyKey);
    }

    @Test
    @DisplayName("사용자 ID가 없으면 그림일기 생성을 명령할 수 없다")
    void rejectNullUserId() {
        assertThatThrownBy(() -> createCommand(null, UUID.randomUUID(), LocalDate.now(), "일기", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("사용자 ID");
    }

    @Test
    @DisplayName("일기 ID가 없으면 그림일기 생성을 명령할 수 없다")
    void rejectNullDiaryId() {
        assertThatThrownBy(() -> createCommand(UUID.randomUUID(), null, LocalDate.now(), "일기", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("일기 ID");
    }

    @Test
    @DisplayName("일기 날짜가 없으면 그림일기 생성을 명령할 수 없다")
    void rejectNullDiaryDate() {
        assertThatThrownBy(() -> createCommand(UUID.randomUUID(), UUID.randomUUID(), null, "일기", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("일기 날짜");
    }

    @Test
    @DisplayName("일기 내용이 비어 있으면 그림일기 생성을 명령할 수 없다")
    void rejectBlankDiaryText() {
        assertThatThrownBy(() -> createCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocalDate.now(),
                " ",
                UUID.randomUUID()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("일기 내용");
    }

    @Test
    @DisplayName("멱등성 키가 없으면 그림일기 생성을 명령할 수 없다")
    void rejectNullIdempotencyKey() {
        assertThatThrownBy(() -> createCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocalDate.now(),
                "일기",
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("멱등성 키");
    }

    private GenerateDiaryImageCommand createCommand(
            UUID userId,
            UUID diaryId,
            LocalDate diaryDate,
            String diaryText,
            UUID idempotencyKey
    ) {
        return new GenerateDiaryImageCommand(userId, diaryId, diaryDate, diaryText, idempotencyKey);
    }
}
