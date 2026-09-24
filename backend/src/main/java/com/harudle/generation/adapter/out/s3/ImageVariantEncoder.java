package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.domain.ImageVariant;
import java.util.Map;

public interface ImageVariantEncoder {

    Map<ImageVariant, GeneratedImage> encode(GeneratedImage image);
}
