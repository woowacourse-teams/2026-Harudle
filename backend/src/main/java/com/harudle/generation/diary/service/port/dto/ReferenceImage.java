package com.harudle.generation.diary.service.port.dto;

import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

public record ReferenceImage(
        Resource resource,
        MediaType mediaType
) {

    public ReferenceImage {
        ImagePayloadValidator.validate(resource, mediaType, "참조 이미지");
    }
}
