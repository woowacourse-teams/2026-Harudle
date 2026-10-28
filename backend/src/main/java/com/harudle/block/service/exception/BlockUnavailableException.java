package com.harudle.block.service.exception;

public final class BlockUnavailableException extends RuntimeException {
    public BlockUnavailableException() {
        super("차단한 회원 정보를 조회할 수 없습니다.");
    }
}
