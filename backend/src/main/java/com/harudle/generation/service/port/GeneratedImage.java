package com.harudle.generation.service.port;

import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

public record GeneratedImage(
        Resource resource,
        MediaType mediaType
) {

    public GeneratedImage {
        ImagePayloadValidator.validate(resource, mediaType, "생성 이미지");
    }
}
