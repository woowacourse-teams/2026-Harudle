package com.harudle.generation.diary.service.port;

import com.harudle.generation.diary.domain.StoryboardProviderInfo;
import com.harudle.generation.diary.service.port.dto.GeneratedStoryboard;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;

public interface StoryboardGenerator {

    GeneratedStoryboard generate(StoryboardGenerationRequest request);

    default StoryboardProviderInfo providerInfo() {
        return null;
    }
}
