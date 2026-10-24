package com.harudle.notification.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

public record ReadNotificationRequest(
        @NotNull(message = "읽음 상태는 필수입니다.")
        @AssertTrue(message = "알림은 읽음 상태로만 변경할 수 있습니다.")
        @JsonDeserialize(using = StrictNotificationReadDeserializer.class)
        @Schema(description = "읽음 처리. true만 허용하며 읽음 취소는 제공하지 않습니다.", allowableValues = "true") Boolean read
) {}
