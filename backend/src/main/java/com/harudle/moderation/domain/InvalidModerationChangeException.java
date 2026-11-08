package com.harudle.moderation.domain;

public final class InvalidModerationChangeException extends RuntimeException {
    public InvalidModerationChangeException() {
        super("조치 사유와 버전을 확인해 주세요.");
    }
}
