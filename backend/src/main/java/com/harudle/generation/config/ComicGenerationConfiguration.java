package com.harudle.generation.config;

import com.harudle.generation.repository.ComicGenerationRepository;
import com.harudle.generation.repository.GenerationPromptRepository;
import com.harudle.generation.service.ClaimedComicGenerationService;
import com.harudle.generation.service.ComicGenerationCompletionService;
import com.harudle.generation.service.RequestFingerprintGenerator;
import com.harudle.generation.service.port.ComicImageGenerator;
import com.harudle.generation.service.port.ImageStorage;
import com.harudle.generation.service.port.StoryboardGenerator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ComicGenerationConfiguration {

    @Bean
    ClaimedComicGenerationService claimedComicGenerationService(
            RequestFingerprintGenerator requestFingerprintGenerator,
            GenerationPromptRepository generationPromptRepository,
            ComicGenerationRepository comicGenerationRepository,
            ObjectProvider<StoryboardGenerator> storyboardGeneratorProvider,
            ObjectProvider<ComicImageGenerator> comicImageGeneratorProvider,
            ObjectProvider<ImageStorage> imageStorageProvider,
            ComicGenerationCompletionService completionService
    ) {
        return new ClaimedComicGenerationService(
                requestFingerprintGenerator,
                generationPromptRepository,
                comicGenerationRepository,
                storyboardGeneratorProvider,
                comicImageGeneratorProvider,
                imageStorageProvider,
                completionService
        );
    }

}
