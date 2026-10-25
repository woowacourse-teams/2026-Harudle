package com.harudle.profile.service;

import com.harudle.auth.application.ProfileImageStorageException;
import com.harudle.auth.domain.policy.ProfileImageCodePolicy;
import com.harudle.auth.infrastructure.S3ProfileImageStorage;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import com.harudle.profile.configuration.ProfileImageProperties;
import java.net.URI;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class ProfileImageUrlResolver {

    private final ProfileImageProperties properties;
    private final ObjectProvider<S3ProfileImageStorage> storageProvider;

    public ProfileImageUrlResolver(
            ProfileImageProperties properties,
            ObjectProvider<S3ProfileImageStorage> storageProvider
    ) {
        this.properties = properties;
        this.storageProvider = storageProvider;
    }

    public ResolvedImage resolve(@Nullable Short imageCode, @Nullable String objectKey) {
        if (objectKey != null) {
            return resolveUploaded(objectKey);
        }
        if (imageCode == null) {
            return new ResolvedImage(null, null);
        }

        return new ResolvedImage(resolve(imageCode), null);
    }

    private ResolvedImage resolveUploaded(String objectKey) {
        S3ProfileImageStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new ProfileImageStorageException("프로필 이미지 저장소가 구성되지 않았습니다.", objectKey, null);
        }
        ImageAccessUrl accessUrl = storage.createAccessUrl(objectKey);

        return new ResolvedImage(accessUrl.url(), accessUrl.expiresAt());
    }

    public URI resolve(int imageCode) {
        short validatedCode = ProfileImageCodePolicy.validateAndConvert(imageCode);
        return UriComponentsBuilder.fromUri(properties.imageBaseUrl())
                .pathSegment(validatedCode + ".png")
                .build()
                .toUri();
    }

    public record ResolvedImage(@Nullable URI url, @Nullable Instant expiresAt) {
    }
}
