package com.harudle.report.presentation;

import com.harudle.report.service.ReportReceipt;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record ReportResponse(UUID id, @Schema(allowableValues = "RECEIVED") String status, boolean duplicate) {
    static ReportResponse from(ReportReceipt receipt) {
        return new ReportResponse(receipt.id(), "RECEIVED", receipt.duplicate());
    }
}
