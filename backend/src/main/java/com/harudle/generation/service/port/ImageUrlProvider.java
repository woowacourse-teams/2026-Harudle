package com.harudle.generation.service.port;

@FunctionalInterface
public interface ImageUrlProvider {

    ImageAccessUrl createAccessUrl(String imageObjectKey);
}
