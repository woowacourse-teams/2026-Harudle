package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.authentication.BadCredentialsException;

@ExtendWith(OutputCaptureExtension.class)
class OAuthEventLoggerTest {

    private final OAuthEventLogger oAuthEventLogger = new OAuthEventLogger();

    @Test
    @DisplayName("예상 가능한 OAuth 로그인 거절은 INFO 공통 필드로 기록한다")
    void logExpectedRejection(CapturedOutput output) {
        oAuthEventLogger.infoRejected("kakao", OAuthFailureReason.PROVIDER_ACCESS_DENIED);

        assertThat(output)
                .contains(" INFO ")
                .contains("event=oauth_login_rejected")
                .contains("provider=kakao")
                .contains("reason=PROVIDER_ACCESS_DENIED")
                .doesNotContain("exceptionType=");
    }

    @Test
    @DisplayName("OAuth 공급자 인증 실패는 예외 메시지와 스택 없이 WARN으로 기록한다")
    void logProviderFailureWithoutSensitiveException(CapturedOutput output) {
        BadCredentialsException exception = new BadCredentialsException(
                "access_token=must-not-be-logged"
        );

        oAuthEventLogger.warnFailure(
                "kakao",
                OAuthFailureReason.PROVIDER_AUTHENTICATION_FAILED,
                exception
        );

        assertThat(output)
                .contains(" WARN ")
                .contains("event=oauth_login_failure")
                .contains("provider=kakao")
                .contains("reason=PROVIDER_AUTHENTICATION_FAILED")
                .contains("exceptionType=BadCredentialsException")
                .doesNotContain("must-not-be-logged")
                .doesNotContain("BadCredentialsException:");
    }

    @Test
    @DisplayName("OAuth 내부 오류는 안전한 provider와 스택트레이스를 포함해 ERROR로 기록한다")
    void logInternalFailureWithStackTrace(CapturedOutput output) {
        IllegalStateException exception = new IllegalStateException("내부 정합성 오류");

        oAuthEventLogger.errorFailure(
                "kakao\nforged=value",
                OAuthFailureReason.INTERNAL_CONSISTENCY_ERROR,
                exception
        );

        assertThat(output)
                .contains(" ERROR ")
                .contains("event=oauth_login_failure")
                .contains("provider=unknown")
                .contains("reason=INTERNAL_CONSISTENCY_ERROR")
                .contains("exceptionType=IllegalStateException")
                .contains("java.lang.IllegalStateException: 내부 정합성 오류")
                .doesNotContain("forged=value");
    }
}
