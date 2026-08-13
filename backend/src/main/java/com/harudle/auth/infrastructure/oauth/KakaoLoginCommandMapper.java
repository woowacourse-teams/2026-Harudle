package com.harudle.auth.infrastructure.oauth;

import com.harudle.auth.application.OAuthLoginCommand;
import com.harudle.auth.domain.OAuthProvider;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class KakaoLoginCommandMapper {

    private final ObjectMapper objectMapper;

    public KakaoLoginCommandMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OAuthLoginCommand map(Map<String, Object> attributes) {
        Objects.requireNonNull(attributes, "카카오 사용자 정보는 필수입니다.");

        KakaoUserInfo userInfo = objectMapper.convertValue(
                attributes,
                KakaoUserInfo.class
        );

        return new OAuthLoginCommand(
                OAuthProvider.KAKAO,
                userInfo.providerSubject(),
                userInfo.email(),
                userInfo.nickname()
        );
    }

}
