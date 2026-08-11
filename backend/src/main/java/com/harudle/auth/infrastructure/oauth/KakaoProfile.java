package com.harudle.auth.infrastructure.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoProfile(String nickname) {

    public static KakaoProfile empty() {
        return new KakaoProfile(null);
    }

}
