package com.harudle.auth.infrastructure.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoAccount(String email, KakaoProfile profile) {

    public KakaoAccount {
        if (profile == null) {
            profile = KakaoProfile.empty();
        }
    }

    public static KakaoAccount empty() {
        return new KakaoAccount(
                null,
                KakaoProfile.empty()
        );
    }

    public String nickname() {
        return profile.nickname();
    }

}
