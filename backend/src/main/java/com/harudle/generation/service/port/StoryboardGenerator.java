package com.harudle.generation.service.port;

import com.harudle.generation.domain.Storyboard;

public interface StoryboardGenerator {

    Storyboard generate(StoryboardGenerationRequest request);
}
