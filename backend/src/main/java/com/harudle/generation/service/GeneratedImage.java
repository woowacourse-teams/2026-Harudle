package com.harudle.generation.service;

import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

public record GeneratedImage(
        Resource resource,
        MediaType mediaType
) {

    public GeneratedImage {
        validateResource(resource);
        validateMediaType(mediaType);
    }

    private static void validateResource(Resource resource) {
        if (resource == null || !resource.isReadable()) {
            throw new IllegalArgumentException("읽을 수 있는 생성 이미지 Resource가 필요합니다.");
        }
    }

    private static void validateMediaType(MediaType mediaType) {
        if (mediaType == null || !mediaType.getType().equals("image") || mediaType.isWildcardSubtype()) {
            throw new IllegalArgumentException("생성 이미지의 구체적인 이미지 MediaType이 필요합니다.");
        }
    }
}
