package com.harudle.report.domain;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ReportSubmission(ReportTargetType targetType, UUID targetId, ReportReason reason, String description) {

    public static final int MAX_DESCRIPTION_CODE_POINTS = 500;

    public ReportSubmission {
        if (targetType == null || targetId == null || reason == null) {
            throw new InvalidReportException();
        }
        description = normalizeDescription(description);
    }

    public static String normalizeDescription(@Nullable String value) {
        String normalized = value == null ? "" : value.strip();
        boolean invalidCharacter = normalized.codePoints()
                .anyMatch(codePoint -> codePoint == 0 || (codePoint >= 0xD800 && codePoint <= 0xDFFF));
        if (invalidCharacter || normalized.codePointCount(0, normalized.length()) > MAX_DESCRIPTION_CODE_POINTS) {
            throw new InvalidReportException();
        }
        return normalized;
    }

    @Override
    public String toString() {
        return "ReportSubmission[description=<redacted>]";
    }
}
