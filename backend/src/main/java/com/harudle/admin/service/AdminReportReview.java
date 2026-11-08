package com.harudle.admin.service;

import com.harudle.admin.query.AdminReportStatus;
import com.harudle.admin.service.exception.InvalidAdminReportReviewException;

public record AdminReportReview(AdminReportStatus status, String reason) {

    public static final int MAX_REASON_CODE_POINTS = 500;

    public AdminReportReview {
        if (status == null || status == AdminReportStatus.RECEIVED || reason == null) {
            throw new InvalidAdminReportReviewException();
        }
        reason = reason.strip();
        boolean invalidCharacter = reason.codePoints()
                .anyMatch(codePoint -> codePoint == 0 || (codePoint >= 0xD800 && codePoint <= 0xDFFF));
        int length = reason.codePointCount(0, reason.length());
        if (length < 1 || length > MAX_REASON_CODE_POINTS || invalidCharacter) {
            throw new InvalidAdminReportReviewException();
        }
    }

    @Override
    public String toString() {
        return "AdminReportReview[status=" + status + ", reason=[REDACTED]]";
    }
}
