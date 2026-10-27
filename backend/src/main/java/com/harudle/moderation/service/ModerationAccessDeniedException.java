package com.harudle.moderation.service;

public final class ModerationAccessDeniedException extends RuntimeException {
    public ModerationAccessDeniedException() {
        super("관리자 권한이 필요합니다.");
    }
}
