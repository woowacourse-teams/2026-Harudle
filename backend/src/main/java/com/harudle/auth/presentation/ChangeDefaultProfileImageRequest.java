package com.harudle.auth.presentation;

import com.harudle.auth.domain.policy.ProfileImageCodePolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ChangeDefaultProfileImageRequest(
        @Schema(description = "선택할 기본 프로필 이미지 번호 (1~10)", example = "7")
        @NotNull(
                message = "프로필 이미지 번호는 필수입니다."
        )
        @Min(
                value = ProfileImageCodePolicy.MIN_CODE,
                message = "프로필 이미지 번호는 {value} 이상이어야 합니다."
        )
        @Max(
                value = ProfileImageCodePolicy.MAX_CODE,
                message = "프로필 이미지 번호는 {value} 이하여야 합니다."
        )
        Integer imageCode
) {
}
