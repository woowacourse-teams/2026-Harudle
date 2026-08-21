package com.harudle.auth.infrastructure.oauth;

import com.harudle.auth.application.OAuthLoginCommand;
import com.harudle.auth.domain.OAuthProvider;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class KakaoLoginCommandMapper {

    private final ObjectMapper objectMapper;

    public KakaoLoginCommandMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OAuthLoginCommand map(Map<String, Object> attributes) {
        if (attributes == null) {
            throw new InvalidOAuthProfileException();
        }

        try {
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
        } catch (IllegalArgumentException | JacksonException exception) {
            throw new InvalidOAuthProfileException(exception);
        }
    }

}
