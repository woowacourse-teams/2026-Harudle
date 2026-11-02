package com.harudle.admin.presentation.dto;

import com.harudle.admin.query.AdminReportSnapshot;
import com.harudle.admin.query.AdminReportTarget;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record AdminReportDetailResponse(AdminReportSnapshot report, TargetResponse target) {

    public record TargetResponse(
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
            @Nullable URI imageUrl,
            @Nullable Instant imageUrlExpiresAt,
            @Nullable String commentContent
    ) {

        public static TargetResponse from(AdminReportTarget target, @Nullable URI imageUrl,
                @Nullable Instant imageUrlExpiresAt) {
            return new TargetResponse(target.exists(), target.authorId(), target.authorNickname(),
                    target.authorDeleted(), target.deleted(), target.parentDeleted(), target.hidden(),
                    target.parentHidden(), target.publiclyVisible(), target.feedId(), target.title(),
                    imageUrl, imageUrlExpiresAt, target.commentContent());
        }

        @Override
        public String toString() {
            return "TargetResponse[exists=" + exists + ", commentContent=[REDACTED], imageUrl=[REDACTED]]";
        }
    }
}
