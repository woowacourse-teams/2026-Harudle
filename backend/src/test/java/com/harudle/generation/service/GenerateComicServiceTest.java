package com.harudle.generation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.harudle.generation.domain.ComicGeneration;
import com.harudle.generation.domain.GenerationPrompt;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.domain.StoryPanel;
import com.harudle.generation.domain.Storyboard;
import com.harudle.generation.repository.ComicGenerationRepository;
import com.harudle.generation.repository.GenerationPromptRepository;

@SpringJUnitConfig({GenerateComicService.class, RequestFingerprintGenerator.class})
class GenerateComicServiceTest {

    @MockitoBean
    private GenerationPromptRepository generationPromptRepository;

    @MockitoBean
    private ComicGenerationRepository comicGenerationRepository;

    @MockitoBean
    private StoryboardGenerator storyboardGenerator;

    @MockitoBean
    private ComicImageGenerator comicImageGenerator;

    @MockitoBean
    private ImageStorage imageStorage;

    @Autowired
    private GenerateComicService generateComicService;

    @Test
    @DisplayName("일기로 스토리보드와 이미지를 생성하고 성공 결과를 반환한다")
    void generateComic() {
        GenerateComicCommand command = createCommand();
        GenerationPrompt prompt = createPrompt();
        Storyboard storyboard = createStoryboard();
        ReferenceImage referenceImage = createReferenceImage();
        GeneratedImage generatedImage = createGeneratedImage();
        AtomicInteger saveCount = new AtomicInteger();

        when(generationPromptRepository.findFirstByOrderByIdDesc()).thenReturn(Optional.of(prompt));
        when(comicGenerationRepository.saveAndFlush(any(ComicGeneration.class)))
                .thenAnswer(invocation -> {
                    ComicGeneration generation = invocation.getArgument(0);
                    if (saveCount.getAndIncrement() == 0) {
                        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PROCESSING);
                    } else {
                        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
                    }
                    return generation;
                });
        when(storyboardGenerator.generate(any(StoryboardGenerationRequest.class))).thenReturn(storyboard);
        when(imageStorage.load(prompt.getImageAssetObjectKey())).thenReturn(referenceImage);
        when(comicImageGenerator.generate(any(ComicImageGenerationRequest.class))).thenReturn(generatedImage);
        when(imageStorage.store(generatedImage)).thenReturn("generated/comic.png");

        ComicGenerationResult result = generateComicService.generate(command);

        assertThat(result.status()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(result.title()).isEqualTo(storyboard.title());
        assertThat(result.imageObjectKey()).isEqualTo("generated/comic.png");
        assertThat(result.completedAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(result.newlyCreated()).isTrue();
        assertThat(saveCount).hasValue(2);

        InOrder inOrder = inOrder(
                generationPromptRepository,
                comicGenerationRepository,
                storyboardGenerator,
                imageStorage,
                comicImageGenerator
        );
        inOrder.verify(generationPromptRepository).findFirstByOrderByIdDesc();
        inOrder.verify(comicGenerationRepository).saveAndFlush(any(ComicGeneration.class));
        inOrder.verify(storyboardGenerator).generate(new StoryboardGenerationRequest(
                command.diaryText(),
                prompt.getStoryboardPromptText()
        ));
        inOrder.verify(imageStorage).load(prompt.getImageAssetObjectKey());
        inOrder.verify(comicImageGenerator).generate(new ComicImageGenerationRequest(
                storyboard,
                prompt.getImageStylePromptText(),
                referenceImage
        ));
        inOrder.verify(imageStorage).store(generatedImage);
        inOrder.verify(comicGenerationRepository).saveAndFlush(any(ComicGeneration.class));
    }

    private GenerateComicCommand createCommand() {
        return new GenerateComicCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocalDate.of(2026, 8, 10),
                "오늘 친구와 카페에 갔다.",
                UUID.randomUUID()
        );
    }

    private GenerationPrompt createPrompt() {
        GenerationPrompt prompt = mock(GenerationPrompt.class);
        when(prompt.getId()).thenReturn(1L);
        when(prompt.getStoryboardPromptText()).thenReturn("스토리보드 프롬프트");
        when(prompt.getImageStylePromptText()).thenReturn("이미지 스타일 프롬프트");
        when(prompt.getImageAssetObjectKey()).thenReturn("references/style.png");
        return prompt;
    }

    private Storyboard createStoryboard() {
        return new Storyboard(
                "친구와 보낸 하루",
                "같은 주인공이 모든 패널에 등장한다.",
                List.of(
                        createPanel(1, "첫 번째 캡션"),
                        createPanel(2, "두 번째 캡션"),
                        createPanel(3, "세 번째 캡션"),
                        createPanel(4, "네 번째 캡션")
                )
        );
    }

    private StoryPanel createPanel(int panelNumber, String caption) {
        return new StoryPanel(
                panelNumber,
                caption,
                "장면 " + panelNumber,
                "등장인물 " + panelNumber,
                "감정 " + panelNumber,
                List.of("소품 " + panelNumber)
        );
    }

    private ReferenceImage createReferenceImage() {
        return new ReferenceImage(
                new ByteArrayResource("reference".getBytes(StandardCharsets.UTF_8)),
                MediaType.IMAGE_PNG
        );
    }

    private GeneratedImage createGeneratedImage() {
        return new GeneratedImage(
                new ByteArrayResource("generated".getBytes(StandardCharsets.UTF_8)),
                MediaType.IMAGE_PNG
        );
    }
}
