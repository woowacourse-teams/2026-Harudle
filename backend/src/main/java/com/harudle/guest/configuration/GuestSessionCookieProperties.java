package com.harudle.guest.configuration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.guest.cookie")
public record GuestSessionCookieProperties(
        @NotBlank String name,
        @NotBlank String path,
        boolean secure,
        @NotBlank String sameSite
) {

    private static final Set<String> SUPPORTED_SAME_SITE_VALUES = Set.of(
            "Strict",
            "Lax",
            "None"
    );

    @AssertTrue(message = "게스트 세션 Cookie 경로는 /로 시작해야 합니다.")
    public boolean isPathAbsolute() {
        return path != null && path.startsWith("/");
    }

    @AssertTrue(message = "게스트 세션 Cookie SameSite 설정이 올바르지 않습니다.")
    public boolean isSameSiteSupported() {
        return sameSite != null && SUPPORTED_SAME_SITE_VALUES.contains(sameSite);
    }

    @AssertTrue(message = "SameSite=None Cookie는 Secure 설정이 필요합니다.")
    public boolean isNoneCookieSecure() {
        return !"None".equals(sameSite) || secure;
    }
}
