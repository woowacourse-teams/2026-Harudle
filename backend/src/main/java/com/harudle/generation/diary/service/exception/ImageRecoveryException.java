package com.harudle.generation.diary.service.exception;

public final class ImageRecoveryException extends RuntimeException {
    private final Reason reason;

    public ImageRecoveryException(Reason reason) {
        super("R2 이미지 복구를 완료하지 못했습니다: " + reason.name());
        this.reason = reason;
    }

    public ImageRecoveryException(Reason reason, Throwable cause) {
        super("R2 이미지 복구를 완료하지 못했습니다: " + reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        BACKUP_NOT_FOUND, AMBIGUOUS_BACKUP, BACKUP_CHANGED, INVALID_CONTENT,
        CONTENT_READ_FAILED, ORIGINAL_CONFLICT, VERIFICATION_FAILED
    }
}
