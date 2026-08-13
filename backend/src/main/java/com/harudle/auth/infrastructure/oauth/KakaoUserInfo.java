package com.harudle.auth.infrastructure.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoUserInfo(Long id, @JsonProperty("kakao_account") KakaoAccount kakaoAccount) {

    public KakaoUserInfo {
        if (kakaoAccount == null) {
            kakaoAccount = KakaoAccount.empty();
        }
    }

    public String providerSubject() {
        validateId();

        return id.toString();
    }

    public String email() {
        return kakaoAccount.email();
    }

    public String nickname() {
        return kakaoAccount.nickname();
    }

    private void validateId() {
        if (id == null) {
            throw new IllegalArgumentException("카카오 사용자 정보에 id가 없습니다.");
        }

        if (id > 0) {
            return;
        }

        throw new IllegalArgumentException("카카오 사용자 id는 양수여야 합니다.");
    }
}
