package com.harudle.generation.service.port;

import java.util.UUID;

public interface ImageStorage {

    ReferenceImage load(String imageObjectKey);

    String store(UUID generationId, GeneratedImage generatedImage);

    void delete(String imageObjectKey);
}
