package com.harudle.generation.loadtest;

import com.harudle.generation.diary.domain.GenerationTokenUsage;
import com.harudle.generation.diary.domain.StoryPanel;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.domain.StoryboardProviderInfo;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.dto.GeneratedStoryboard;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;

public final class LoadTestAiStub {

    public static final Duration DELAY = Duration.ofSeconds(30);

    public GeneratedStoryboard storyboard() {
        List<StoryPanel> panels = IntStream.rangeClosed(1, 4)
                .mapToObj(number -> new StoryPanel(number, "부하 테스트 " + number,
                        "테스트 장면", "테스트 회원", "평온", List.of()))
                .toList();
        return new GeneratedStoryboard(new Storyboard("부하 테스트 일기", "같은 테스트 회원", panels),
                new GenerationTokenUsage(0, 0, 0, 0), new StoryboardProviderInfo("stub", "fixed-30s"));
    }

    public GeneratedImage generateImage(ReferenceImage referenceImage) {
        try {
            Thread.sleep(DELAY);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiGenerationException(AiGenerationErrorType.TIMEOUT, "AI Stub 대기가 중단되었습니다.", exception);
        }
        return new GeneratedImage(new ClassPathResource("load-test-diary.png"), MediaType.IMAGE_PNG);
    }
}
