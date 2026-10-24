package com.harudle.comment.presentation;

import com.harudle.common.validation.CodePointLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateCommentRequest(
        @NotBlank(message = "댓글 내용은 필수입니다.")
        @CodePointLength(min = 1, max = 100, message = "댓글은 1자 이상 100자 이하여야 합니다.")
        @Pattern(regexp = "[^\\x00\\p{Cs}]*", message = "댓글에 사용할 수 없는 문자가 포함되어 있습니다.")
        @Schema(description = "댓글 일반 텍스트. 앞뒤 공백 제거 후 1~100 Unicode 코드 포인트") String content
) {}
