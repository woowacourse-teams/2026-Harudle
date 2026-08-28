package com.harudle.admin.presentation.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AdminGenerationUsageRestoreRequest(
        @NotNull(message = "복구 횟수는 필수입니다.")
        @Min(value = 1, message = "복구 횟수는 1 이상이어야 합니다.")
        Integer count
) {
}
