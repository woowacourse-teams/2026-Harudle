package com.harudle.moderation.domain;

public record ModerationChange(boolean restricted, long expectedVersion, String reason) {
    public ModerationChange {
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE || reason == null) {
            throw new InvalidModerationChangeException();
        }
        reason = reason.strip();
        if (reason.isEmpty() || reason.codePointCount(0, reason.length()) > 500) {
            throw new InvalidModerationChangeException();
        }
        for (int i = 0; i < reason.length(); i++) {
            char character = reason.charAt(i);
            if (character == 0 || Character.isLowSurrogate(character)) {
                throw new InvalidModerationChangeException();
            }
            if (Character.isHighSurrogate(character)
                    && (++i >= reason.length() || !Character.isLowSurrogate(reason.charAt(i)))) {
                throw new InvalidModerationChangeException();
            }
        }
    }
}
