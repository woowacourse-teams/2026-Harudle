package com.harudle.generation.service.port;

import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

final class ImagePayloadValidator {

    private static final String IMAGE_MEDIA_TYPE = "image";

    private ImagePayloadValidator() {
    }

    static void validate(Resource resource, MediaType mediaType, String imageName) {
        if (resource == null || !resource.isReadable()) {
            throw new IllegalArgumentException("읽을 수 있는 " + imageName + " Resource가 필요합니다.");
        }
        if (mediaType == null
                || !IMAGE_MEDIA_TYPE.equals(mediaType.getType())
                || mediaType.isWildcardSubtype()) {
            throw new IllegalArgumentException(imageName + "의 구체적인 이미지 MediaType이 필요합니다.");
        }
    }
}
