package com.harudle.block.service.exception;

public final class InvalidBlockCursorException extends RuntimeException {
    public InvalidBlockCursorException() {
        super("차단 목록 조회 위치가 올바르지 않습니다.");
    }
}
