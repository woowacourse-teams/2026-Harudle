package com.harudle.generation.diary.service.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface GeneratedImageCatalog {

    Page list(String continuationToken);

    record Image(UUID generationId, String objectKey, Instant lastModified) {
    }

    record Page(List<Image> images, String nextToken) {
        public Page {
            images = List.copyOf(images);
        }
    }
}
