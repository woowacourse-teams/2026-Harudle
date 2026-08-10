package com.harudle.diary.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiaryTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 6);

    @Test
    @DisplayName("일기 내용의 앞뒤 공백을 제거해 생성한다")
    void createNormalizesSourceText() {
        Diary diary = Diary.create(USER_ID, DIARY_DATE, "  오늘 친구와 카페에 갔다.  ");

        assertThat(diary.getUserId()).isEqualTo(USER_ID);
        assertThat(diary.getDiaryDate()).isEqualTo(DIARY_DATE);
        assertThat(diary.getSourceText()).isEqualTo("오늘 친구와 카페에 갔다.");
        assertThat(diary.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("일기 내용은 공백을 제외하고 1자 이상이어야 한다")
    void createRejectsBlankSourceText() {
        assertThatThrownBy(() -> Diary.create(USER_ID, DIARY_DATE, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("일기 내용은 필수입니다.");
    }

    @Test
    @DisplayName("일기 내용은 유니코드 코드 포인트 기준 300자 이하여야 한다")
    void createRejectsSourceTextOverThreeHundredCodePoints() {
        String sourceText = "🙂".repeat(301);

        assertThatThrownBy(() -> Diary.create(USER_ID, DIARY_DATE, sourceText))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("일기 내용은 300자 이하여야 합니다.");
    }

    @Test
    @DisplayName("일기를 소프트 삭제한다")
    void deleteMarksDiaryAsDeleted() {
        Diary diary = Diary.create(USER_ID, DIARY_DATE, "오늘의 일기");
        Instant deletedAt = Instant.parse("2026-08-06T12:00:00Z");

        diary.delete(deletedAt);

        assertThat(diary.isDeleted()).isTrue();
        assertThat(diary.getDeletedAt()).isEqualTo(deletedAt);
    }

    @Test
    @DisplayName("이미 삭제한 일기를 다시 삭제해도 최초 삭제 시각을 유지한다")
    void deleteIsIdempotent() {
        Diary diary = Diary.create(USER_ID, DIARY_DATE, "오늘의 일기");
        Instant firstDeletedAt = Instant.parse("2026-08-06T12:00:00Z");

        diary.delete(firstDeletedAt);
        diary.delete(firstDeletedAt.plusSeconds(1));

        assertThat(diary.getDeletedAt()).isEqualTo(firstDeletedAt);
    }
}
