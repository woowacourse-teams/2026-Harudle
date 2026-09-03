package com.harudle.generation.diary.service.port;

import com.harudle.generation.diary.service.port.dto.DiaryImageGenerationRequest;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;

public interface DiaryImageGenerator {

    GeneratedImage generate(DiaryImageGenerationRequest request);
}
