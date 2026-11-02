package com.harudle.auth.presentation;

import com.harudle.auth.domain.policy.ProfileImageCodePolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import org.springframework.web.multipart.MultipartFile;

public record ChangeProfileRequest(
        @Schema(description = "변경할 닉네임. 생략하면 유지", example = "새닉네임")
        @Nullable String nickname,
        @Schema(description = "선택할 기본 이미지 번호. image와 함께 전송 불가", example = "7")
        @Min(value = ProfileImageCodePolicy.MIN_CODE, message = "프로필 이미지 번호는 {value} 이상이어야 합니다.")
        @Max(value = ProfileImageCodePolicy.MAX_CODE, message = "프로필 이미지 번호는 {value} 이하여야 합니다.")
        @Nullable Integer imageCode,
        @Schema(description = "개인 사진. imageCode와 함께 전송 불가")
        @Nullable MultipartFile image
) {
}
