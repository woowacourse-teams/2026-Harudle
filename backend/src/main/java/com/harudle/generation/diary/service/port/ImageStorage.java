package com.harudle.generation.diary.service.port;

import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.util.UUID;

public interface ImageStorage {

    ReferenceImage load(String imageObjectKey);

    String store(UUID generationId, GeneratedImage generatedImage);

    boolean exists(String imageObjectKey);

    boolean restoreIfMissing(String imageObjectKey, GeneratedImage generatedImage);

    boolean restoreOptimizedIfMissing(String detailKey, GeneratedImage generatedImage);

    boolean restoreThumbnailFromDetail(String detailKey);

    void delete(String imageObjectKey);
}
