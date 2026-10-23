package com.harudle.auth.infrastructure.oauth;

import com.harudle.auth.application.OAuthLoginCommand;
import com.harudle.auth.domain.OAuthProvider;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class AppleLoginCommandMapper implements OAuthLoginCommandMapper {

    private static final String REGISTRATION_ID = "apple";

    private final ObjectMapper objectMapper;

    public AppleLoginCommandMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String registrationId) {
        return REGISTRATION_ID.equals(registrationId);
    }

    @Override
    public OAuthLoginCommand map(Map<String, Object> attributes) {
        if (attributes == null) {
            throw new InvalidOAuthProfileException();
        }

        try {
            AppleUserInfo userInfo = objectMapper.convertValue(
                    attributes,
                    AppleUserInfo.class
            );

            return new OAuthLoginCommand(
                    OAuthProvider.APPLE,
                    userInfo.sub(),
                    userInfo.email(),
                    null
            );
        } catch (IllegalArgumentException | JacksonException exception) {
            throw new InvalidOAuthProfileException(exception);
        }
    }

}
