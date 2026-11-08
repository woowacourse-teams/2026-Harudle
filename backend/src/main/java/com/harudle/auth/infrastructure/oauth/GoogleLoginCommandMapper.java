package com.harudle.auth.infrastructure.oauth;

import com.harudle.auth.application.OAuthLoginCommand;
import com.harudle.auth.domain.OAuthProvider;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class GoogleLoginCommandMapper implements OAuthLoginCommandMapper {

    private static final String REGISTRATION_ID = "google";

    private final ObjectMapper objectMapper;

    public GoogleLoginCommandMapper(ObjectMapper objectMapper) {
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
            GoogleUserInfo userInfo = objectMapper.convertValue(
                    attributes,
                    GoogleUserInfo.class
            );

            return new OAuthLoginCommand(
                    OAuthProvider.GOOGLE,
                    userInfo.sub(),
                    userInfo.email(),
                    userInfo.name()
            );
        } catch (IllegalArgumentException | JacksonException exception) {
            throw new InvalidOAuthProfileException(exception);
        }
    }

}
