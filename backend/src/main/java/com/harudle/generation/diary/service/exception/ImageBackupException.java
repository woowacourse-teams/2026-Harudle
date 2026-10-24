package com.harudle.generation.diary.service.exception;

import java.io.Serial;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class ImageBackupException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;
    private final Reason reason;

    public ImageBackupException(Reason reason) {
        this(reason, null);
    }

    public ImageBackupException(Reason reason, @Nullable Throwable cause) {
        super("이미지 백업 처리에 실패했습니다.", cause);
        this.reason = Objects.requireNonNull(reason);
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        BACKUP_NOT_FOUND,
        VERIFICATION_FAILED,
        INVALID_CONTENT_SIZE,
        CONTENT_READ_FAILED
    }
}
