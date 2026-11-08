package com.harudle.report.presentation;

import com.harudle.common.validation.CodePointLength;
import com.harudle.report.domain.ReportReason;
import com.harudle.report.domain.ReportSubmission;
import com.harudle.report.domain.ReportTargetType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record SubmitReportRequest(
        @NotNull ReportTargetType targetType,
        @NotNull UUID targetId,
        @NotNull ReportReason reason,
        @Nullable @CodePointLength(max = ReportSubmission.MAX_DESCRIPTION_CODE_POINTS)
        @Pattern(regexp = "[^\\x00\\p{Cs}]*", message = "신고 내용에 사용할 수 없는 문자가 포함되어 있습니다.")
        @Schema(description = "선택 입력. 앞뒤 공백 제거 후 최대 500 Unicode 코드 포인트의 일반 텍스트") String description
) {
    ReportSubmission toSubmission() {
        return new ReportSubmission(targetType, targetId, reason, description);
    }

    @Override
    public String toString() {
        return "SubmitReportRequest[description=<redacted>]";
    }
}
