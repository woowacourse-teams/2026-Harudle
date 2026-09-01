package com.harudle.generation.diary.service.port;

import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;

@FunctionalInterface
public interface ImageUrlProvider {

    ImageAccessUrl createAccessUrl(String imageObjectKey);
}
