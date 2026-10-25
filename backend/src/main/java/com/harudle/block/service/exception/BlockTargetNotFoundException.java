package com.harudle.block.service.exception;

public final class BlockTargetNotFoundException extends RuntimeException {
    public BlockTargetNotFoundException() {
        super("차단할 회원을 찾을 수 없습니다.");
    }
}
