package com.harudle.generation.service.port;

public interface ImageStorage {

    ReferenceImage load(String imageObjectKey);

    String store(GeneratedImage generatedImage);
}
