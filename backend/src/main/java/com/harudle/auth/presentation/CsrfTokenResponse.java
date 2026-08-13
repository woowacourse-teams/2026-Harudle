package com.harudle.auth.presentation;

public record CsrfTokenResponse(String token) {

    public CsrfTokenResponse {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("CSRF Token은 필수입니다.");
        }

        token = token.trim();
    }
}
