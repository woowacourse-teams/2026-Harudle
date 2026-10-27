package com.harudle.auth.presentation;

import jakarta.validation.constraints.NotBlank;

public record ChangeNicknameRequest(
        @NotBlank(message = "닉네임은 필수입니다.") String nickname
) {
}
