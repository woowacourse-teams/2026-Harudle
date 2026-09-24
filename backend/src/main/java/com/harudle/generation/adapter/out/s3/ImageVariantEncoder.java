package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.diary.service.port.dto.GeneratedImage;

public interface ImageVariantEncoder {

    Variants encode(GeneratedImage image);

    record Variants(GeneratedImage detail, GeneratedImage thumbnail) {
    }
}
