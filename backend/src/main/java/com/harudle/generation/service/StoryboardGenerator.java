package com.harudle.generation.service;

import com.harudle.generation.domain.Storyboard;

public interface StoryboardGenerator {

    Storyboard generate(StoryboardGenerationRequest request);
}
