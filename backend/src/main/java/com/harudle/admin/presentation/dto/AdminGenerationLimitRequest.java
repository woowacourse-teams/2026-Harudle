package com.harudle.admin.presentation.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AdminGenerationLimitRequest(
        @NotNull(message = "생성 한도는 필수입니다.")
        @Min(value = 1, message = "생성 한도는 1 이상이어야 합니다.")
        Integer limitCount
) {
}
