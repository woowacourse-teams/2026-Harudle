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
    private static final UUID NEW_OWNER_ID = UUID.fromString("5e2f7c1d-9b3a-4c8e-a6d4-7f1e2b3c4d5e");
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 6);

    @Test
    @DisplayName("일기 내용의 앞뒤 공백을 제거해 생성한다")
    void createNormalizesSourceText() {
        Diary diary = Diary.create(USER_ID, DIARY_DATE, "  오늘 친구와 카페에 갔다.  ");

        assertThat(diary.isOwnedBy(USER_ID)).isTrue();
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
    }

    @Test
    @DisplayName("현재 작성자의 일기를 다른 사용자에게 옮긴다")
    void transferOwnershipChangesOwner() {
        Diary diary = Diary.create(USER_ID, DIARY_DATE, "오늘의 일기");

        diary.transferOwnership(USER_ID, NEW_OWNER_ID);

        assertThat(diary.getUserId()).isEqualTo(NEW_OWNER_ID);
        assertThat(diary.isOwnedBy(NEW_OWNER_ID)).isTrue();
        assertThat(diary.isOwnedBy(USER_ID)).isFalse();
    }

    @Test
    @DisplayName("현재 작성자가 아니면 일기를 옮길 수 없다")
    void transferOwnershipRejectsWrongCurrentOwner() {
        Diary diary = Diary.create(USER_ID, DIARY_DATE, "오늘의 일기");

        assertThatThrownBy(() -> diary.transferOwnership(UUID.randomUUID(), NEW_OWNER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("현재 작성자의 일기만 옮길 수 있습니다.");
        assertThat(diary.getUserId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("삭제된 일기는 옮길 수 없다")
    void transferOwnershipRejectsDeletedDiary() {
        Diary diary = Diary.create(USER_ID, DIARY_DATE, "오늘의 일기");
        diary.delete(Instant.parse("2026-08-06T12:00:00Z"));

        assertThatThrownBy(() -> diary.transferOwnership(USER_ID, NEW_OWNER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("삭제된 일기는 옮길 수 없습니다.");
        assertThat(diary.getUserId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("같은 사용자에게는 일기를 옮길 수 없다")
    void transferOwnershipRejectsSameOwner() {
        Diary diary = Diary.create(USER_ID, DIARY_DATE, "오늘의 일기");

        assertThatThrownBy(() -> diary.transferOwnership(USER_ID, USER_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("같은 사용자에게 일기를 옮길 수 없습니다.");
    }

    @Test
    @DisplayName("일기를 옮길 때 현재 작성자와 새 작성자 ID가 필요하다")
    void transferOwnershipRejectsMissingUserIds() {
        Diary diary = Diary.create(USER_ID, DIARY_DATE, "오늘의 일기");

        assertThatThrownBy(() -> diary.transferOwnership(null, NEW_OWNER_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("사용자 ID는 필수입니다.");
        assertThatThrownBy(() -> diary.transferOwnership(USER_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("사용자 ID는 필수입니다.");
    }

    @Test
    @DisplayName("이미 삭제한 일기를 다시 삭제해도 삭제 상태를 유지한다")
    void deleteIsIdempotent() {
        Diary diary = Diary.create(USER_ID, DIARY_DATE, "오늘의 일기");
        Instant firstDeletedAt = Instant.parse("2026-08-06T12:00:00Z");

        diary.delete(firstDeletedAt);
        diary.delete(firstDeletedAt.plusSeconds(1));

        assertThat(diary.isDeleted()).isTrue();
    }
}
