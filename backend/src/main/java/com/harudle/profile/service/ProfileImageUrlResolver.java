package com.harudle.profile.service;

import com.harudle.auth.domain.policy.ProfileImageCodePolicy;
import com.harudle.profile.configuration.ProfileImageProperties;
import java.net.URI;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class ProfileImageUrlResolver {

    private final ProfileImageProperties properties;

    public ProfileImageUrlResolver(ProfileImageProperties properties) {
        this.properties = properties;
    }

    public URI resolve(int imageCode) {
        short validatedCode = ProfileImageCodePolicy.validateAndConvert(imageCode);
        return UriComponentsBuilder.fromUri(properties.imageBaseUrl())
                .pathSegment(validatedCode + ".png")
                .build()
                .toUri();
    }
}
