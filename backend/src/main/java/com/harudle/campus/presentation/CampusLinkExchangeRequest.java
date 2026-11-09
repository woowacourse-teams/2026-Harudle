package com.harudle.campus.presentation;

import jakarta.validation.constraints.NotNull;

public record CampusLinkExchangeRequest(
        @NotNull String code,
        @NotNull String state
) {
}
