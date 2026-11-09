package com.harudle.diary.service.exception;

/** A new diary claim must roll back rather than store plaintext or a partial generation. */
public final class DiarySourceTextWriteException extends RuntimeException {

    public DiarySourceTextWriteException() {
        super("일기 원문을 안전하게 저장하지 못했습니다.");
    }
}
