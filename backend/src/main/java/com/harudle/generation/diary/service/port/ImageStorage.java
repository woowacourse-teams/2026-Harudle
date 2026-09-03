package com.harudle.generation.diary.service.port;

import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.util.UUID;

public interface ImageStorage {

    ReferenceImage load(String imageObjectKey);

    String store(UUID generationId, GeneratedImage generatedImage);

    void delete(String imageObjectKey);
}
