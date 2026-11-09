package com.harudle.campus.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CampusLinkTokenRequest(
        @Schema(description = "캠퍼스가 만든 state 값. base64url 43~128자")
        @NotNull
        @Pattern(regexp = "[A-Za-z0-9_-]{43,128}")
        String state
) {
}
