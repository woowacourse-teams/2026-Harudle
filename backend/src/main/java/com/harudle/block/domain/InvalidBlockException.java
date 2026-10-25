package com.harudle.block.domain;

public final class InvalidBlockException extends RuntimeException {
    public InvalidBlockException() {
        super("차단 대상이 올바르지 않습니다.");
    }
}
