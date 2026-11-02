package com.harudle.auth.infrastructure.oauth;

import com.harudle.auth.application.OAuthLoginCommand;
import java.util.Map;

public interface OAuthLoginCommandMapper {

    boolean supports(String registrationId);

    OAuthLoginCommand map(Map<String, Object> attributes);
}
