package com.harudle.admin.query;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Current target evidence, which may disappear after physical deletion. Never includes private diary source text. */
public record AdminReportTarget(
        boolean exists,
        @Nullable UUID authorId,
        @Nullable String authorNickname,
        boolean authorDeleted,
        boolean deleted,
        boolean parentDeleted,
        boolean hidden,
        boolean parentHidden,
        boolean publiclyVisible,
        @Nullable UUID feedId,
        @Nullable String title,
        @Nullable String imageObjectKey,
        @Nullable String commentContent
) {

    public static AdminReportTarget missing() {
        return new AdminReportTarget(false, null, null, false, false, false, false, false, false,
                null, null, null, null);
    }

    @Override
    public String toString() {
        return "AdminReportTarget[exists=" + exists + ", deleted=" + deleted + ", commentContent=[REDACTED]]";
    }
}
