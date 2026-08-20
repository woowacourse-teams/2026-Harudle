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
    @DisplayName("OAuth 내부 오류는 민감한 원인 메시지를 제거한 스택트레이스로 ERROR 기록한다")
    void logInternalFailureWithStackTrace(CapturedOutput output) {
        IllegalArgumentException cause = new IllegalArgumentException(
                "refresh_token=cause-must-not-be-logged"
        );
        IllegalStateException exception = new IllegalStateException(
                "access_token=message-must-not-be-logged",
                cause
        );
        exception.setStackTrace(new StackTraceElement[]{
                new StackTraceElement(
                        "com.harudle.auth.infrastructure.oauth.OAuthLoginProcessor",
                        "authenticate",
                        "OAuthLoginProcessor.java",
                        73
                )
        });

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
                .contains("java.lang.RuntimeException: OAuth 인증 처리 실패")
                .contains("at com.harudle.auth.infrastructure.oauth.OAuthLoginProcessor"
                        + ".authenticate(OAuthLoginProcessor.java:73)")
                .doesNotContain("message-must-not-be-logged")
                .doesNotContain("cause-must-not-be-logged")
                .doesNotContain("Caused by:")
                .doesNotContain("forged=value");
    }
}
