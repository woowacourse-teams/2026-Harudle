package com.harudle.diary.infrastructure.migration;

public final class DiarySourceTextMigrationException extends RuntimeException {

    public DiarySourceTextMigrationException() {
        super("일기 원문 전환 작업에 실패했습니다.");
    }
}
