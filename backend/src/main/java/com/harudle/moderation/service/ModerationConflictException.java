package com.harudle.moderation.service;

public final class ModerationConflictException extends RuntimeException {
    public ModerationConflictException() {
        super("다른 관리자가 조치 상태를 변경했습니다. 최신 상태를 확인해 주세요.");
    }
}
