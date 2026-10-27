package com.harudle.moderation.service;

public final class ModerationTargetNotFoundException extends RuntimeException {
    public ModerationTargetNotFoundException() {
        super("조치할 수 있는 대상을 찾을 수 없습니다.");
    }
}
