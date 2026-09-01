package com.harudle.generation.diary.service.port;

import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;

public interface StoryboardGenerator {

    Storyboard generate(StoryboardGenerationRequest request);
}
