package com.harudle.auth.infrastructure.oauth;

final class UnsupportedOAuthProviderException extends RuntimeException {

    UnsupportedOAuthProviderException() {
        super("지원하지 않는 OAuth 제공자입니다.");
    }
}
