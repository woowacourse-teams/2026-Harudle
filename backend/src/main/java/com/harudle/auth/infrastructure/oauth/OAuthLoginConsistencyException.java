package com.harudle.auth.infrastructure.oauth;

final class OAuthLoginConsistencyException extends RuntimeException {

    OAuthLoginConsistencyException(String message) {
        super(message);
    }
}
