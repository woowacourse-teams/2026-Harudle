package com.harudle.diary.infrastructure.migration;

import java.util.Objects;

public final class DiarySourceTextMigrationException extends RuntimeException {

    private final Reason reason;

    public DiarySourceTextMigrationException() {
        this(Reason.DATABASE_OPERATION_FAILED);
    }

    public DiarySourceTextMigrationException(Reason reason) {
        super("일기 원문 전환 작업에 실패했습니다.");
        this.reason = Objects.requireNonNull(reason);
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        DATABASE_OPERATION_FAILED,
        ISOLATION_LEVEL_UNSUPPORTED
    }
}
