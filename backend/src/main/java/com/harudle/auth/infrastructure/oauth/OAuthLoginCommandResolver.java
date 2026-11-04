package com.harudle.auth.infrastructure.oauth;

import com.harudle.auth.application.OAuthLoginCommand;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OAuthLoginCommandResolver {

    private final List<OAuthLoginCommandMapper> mappers;

    public OAuthLoginCommandResolver(List<OAuthLoginCommandMapper> mappers) {
        this.mappers = List.copyOf(mappers);
    }

    public OAuthLoginCommand resolve(String registrationId, Map<String, Object> attributes) {
        List<OAuthLoginCommandMapper> supportedMappers = mappers.stream()
                .filter(mapper -> mapper.supports(registrationId))
                .toList();

        if (supportedMappers.isEmpty()) {
            throw new UnsupportedOAuthProviderException();
        }

        if (supportedMappers.size() > 1) {
            throw new OAuthLoginConsistencyException("로그인 제공자를 담당하는 변환 객체가 중복되었습니다.");
        }

        return supportedMappers.getFirst().map(attributes);
    }

}
