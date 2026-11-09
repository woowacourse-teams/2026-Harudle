package com.harudle.diary.infrastructure.crypto;

public final class DiarySourceTextCryptoException extends RuntimeException {

    public DiarySourceTextCryptoException() {
        super("일기 원문 암호화 처리에 실패했습니다.");
    }
}
