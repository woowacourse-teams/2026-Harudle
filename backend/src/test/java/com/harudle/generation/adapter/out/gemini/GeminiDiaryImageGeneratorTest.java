package com.harudle.generation.adapter.out.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.genai.Models;
import com.google.genai.errors.ClientException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.harudle.common.logging.ExternalApiFailure;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.configuration.GeminiGenerationProperties;
import com.harudle.generation.domain.StoryPanel;
import com.harudle.generation.domain.Storyboard;
import com.harudle.generation.service.exception.AiGenerationErrorType;
import com.harudle.generation.service.exception.AiGenerationException;
import com.harudle.generation.service.port.DiaryImageGenerationRequest;
import com.harudle.generation.service.port.GeneratedImage;
import com.harudle.generation.service.port.ReferenceImage;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

class GeminiDiaryImageGeneratorTest {

    private final Models models = mock(Models.class);
    private final GenerateContentResponse response = mock(GenerateContentResponse.class);
    private final DiaryImagePromptRenderer promptRenderer = new DiaryImagePromptRenderer();
    private final ExternalApiLogger externalApiLogger = mock(ExternalApiLogger.class);
    private final GeminiDiaryImageGenerator generator = new GeminiDiaryImageGenerator(
            models,
            createProperties(),
            promptRenderer,
            new GeminiFailureReporter(
                    new GeminiExceptionTranslator(),
                    externalApiLogger
            )
    );

    @BeforeEach
    void setUp() {
        when(models.generateContent(anyString(), any(Content.class), any(GenerateContentConfig.class)))
                .thenReturn(response);
    }

    @Test
    @DisplayName("스타일 프롬프트와 참조 이미지로 Gemini 그림일기 이미지를 생성한다")
    void generateDiaryImage() throws IOException {
        byte[] generatedImageBytes = "generated-image".getBytes();
        when(response.parts()).thenReturn(ImmutableList.of(
                Part.fromText("image generated"),
                Part.fromBytes(generatedImageBytes, "image/png")
        ));
        DiaryImageGenerationRequest request = createRequest(createReferenceImage());

        GeneratedImage generatedImage = generator.generate(request);

        assertThat(generatedImage.resource().getContentAsByteArray()).isEqualTo(generatedImageBytes);
        assertThat(generatedImage.mediaType()).isEqualTo(MediaType.IMAGE_PNG);

        ArgumentCaptor<Content> contentCaptor = ArgumentCaptor.forClass(Content.class);
        ArgumentCaptor<GenerateContentConfig> configCaptor = ArgumentCaptor.forClass(GenerateContentConfig.class);
        verify(models).generateContent(
                eq("gemini-3.1-flash-image"),
                contentCaptor.capture(),
                configCaptor.capture()
        );

        List<Part> parts = contentCaptor.getValue().parts().orElseThrow();
        assertThat(parts).hasSize(3);
        assertThat(parts.get(0).text()).get().asString()
                .startsWith("[The Only Style Reference]");
        assertThat(parts.get(1).inlineData()).get().satisfies(inlineData -> {
            assertThat(inlineData.data()).contains("reference-image".getBytes());
            assertThat(inlineData.mimeType()).contains("image/png");
        });
        assertThat(parts.get(2).text()).get().asString()
                .startsWith("[Final Task]\nIMAGE STYLE PROMPT\n\nSELECTED STORY:")
                .contains("Panel 1 — TOP LEFT — SETUP:")
                .contains("Caption reads exactly: \"캡션 1\"")
                .contains("This panel contains exactly one readable text block: its assigned caption.")
                .contains("Render exactly six readable text blocks:")
                .contains("FINAL FOOTER LOCK — HIGHEST LAYOUT PRIORITY:")
                .contains("- left: \"# 카페에서 생긴 일\"")
                .contains("- right: \"@harudle.official\"");

        GenerateContentConfig config = configCaptor.getValue();
        assertThat(config.responseModalities()).contains(List.of("TEXT", "IMAGE"));
        assertThat(config.imageConfig()).get()
                .extracting(imageConfig -> imageConfig.aspectRatio().orElseThrow())
                .isEqualTo("1:1");
        verifyNoInteractions(externalApiLogger);
    }

    @Test
    @DisplayName("Gemini 응답에 이미지가 없으면 제공자 오류가 발생한다")
    void rejectResponseWithoutImage() {
        when(response.parts()).thenReturn(ImmutableList.of(Part.fromText("no image")));

        assertThatThrownBy(() -> generator.generate(createRequest(createReferenceImage())))
                .isInstanceOfSatisfying(AiGenerationException.class, exception -> {
                    assertThat(exception.errorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
                    assertThat(exception.getCause()).isInstanceOf(IllegalStateException.class);
                });
        verify(externalApiLogger).error(
                eq(new ExternalApiFailure(
                        "gemini",
                        "image_generation",
                        "RESPONSE_PROCESSING_ERROR",
                        null,
                        null,
                        null
                )),
                any(IllegalStateException.class)
        );
    }

    @ParameterizedTest
    @MethodSource("invalidImageParts")
    @DisplayName("Gemini 응답 이미지가 유효하지 않으면 제공자 오류가 발생한다")
    void rejectInvalidGeneratedImage(Part invalidImagePart) {
        when(response.parts()).thenReturn(ImmutableList.of(invalidImagePart));

        assertThatThrownBy(() -> generator.generate(createRequest(createReferenceImage())))
                .isInstanceOfSatisfying(
                        AiGenerationException.class,
                        exception -> assertThat(exception.errorType())
                                .isEqualTo(AiGenerationErrorType.PROVIDER_ERROR)
                );
    }

    @Test
    @DisplayName("Gemini inline 요청 제한을 넘으면 API를 호출하지 않는다")
    void rejectOversizedInlineRequest() {
        ByteArrayResource oversizedResource = new ByteArrayResource(new byte[]{1}) {
            @Override
            public long contentLength() {
                return 20L * 1024 * 1024;
            }
        };
        ReferenceImage referenceImage = new ReferenceImage(oversizedResource, MediaType.IMAGE_PNG);

        assertThatThrownBy(() -> generator.generate(createRequest(referenceImage)))
                .isInstanceOfSatisfying(AiGenerationException.class, exception -> {
                    assertThat(exception.errorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
                    assertThat(exception.getCause()).isInstanceOf(IllegalArgumentException.class);
                });
        verify(models, never()).generateContent(anyString(), any(Content.class), any(GenerateContentConfig.class));
        verify(externalApiLogger).error(
                eq(new ExternalApiFailure(
                        "gemini",
                        "image_generation",
                        "REQUEST_PREPARATION_ERROR",
                        null,
                        null,
                        null
                )),
                any(IllegalArgumentException.class)
        );
    }

    @Test
    @DisplayName("Gemini 이미지 요청 시간이 초과되면 외부 연동 실패를 기록한다")
    void logImageGenerationTimeout() {
        ClientException cause = new ClientException(504, "GATEWAY_TIMEOUT", "timeout");
        when(models.generateContent(anyString(), any(Content.class), any(GenerateContentConfig.class)))
                .thenThrow(cause);

        assertThatThrownBy(() -> generator.generate(createRequest(createReferenceImage())))
                .isInstanceOfSatisfying(AiGenerationException.class, exception -> {
                    assertThat(exception.errorType()).isEqualTo(AiGenerationErrorType.TIMEOUT);
                    assertThat(exception).hasCause(cause);
                });
        verify(externalApiLogger).warn(
                new ExternalApiFailure(
                        "gemini",
                        "image_generation",
                        "TIMEOUT",
                        "GATEWAY_TIMEOUT",
                        "504",
                        null
                ),
                cause
        );
    }

    private DiaryImageGenerationRequest createRequest(ReferenceImage referenceImage) {
        return new DiaryImageGenerationRequest(
                createStoryboard(),
                "IMAGE STYLE PROMPT",
                referenceImage
        );
    }

    private static ReferenceImage createReferenceImage() {
        return new ReferenceImage(
                new ByteArrayResource("reference-image".getBytes()),
                MediaType.IMAGE_PNG
        );
    }

    private static Storyboard createStoryboard() {
        return new Storyboard(
                "카페에서 생긴 일",
                "One recurring protagonist in casual clothes.",
                List.of(
                        createPanel(1, "캡션 1"),
                        createPanel(2, "캡션 2"),
                        createPanel(3, "캡션 3"),
                        createPanel(4, "캡션 4")
                )
        );
    }

    private static StoryPanel createPanel(int panelNumber, String caption) {
        return new StoryPanel(
                panelNumber,
                caption,
                "Scene " + panelNumber,
                "Characters " + panelNumber,
                "Emotion " + panelNumber,
                List.of("Prop " + panelNumber)
        );
    }

    private static GeminiGenerationProperties createProperties() {
        return new GeminiGenerationProperties(
                "test-api-key",
                "gemini-3.5-flash-lite",
                "gemini-3.1-flash-image",
                "high",
                "1:1",
                4096,
                3,
                Duration.ofSeconds(180)
        );
    }

    private static Stream<Arguments> invalidImageParts() {
        return Stream.of(
                Arguments.of(Part.fromBytes(new byte[0], "image/png")),
                Arguments.of(Part.fromBytes("not-image".getBytes(), "application/octet-stream"))
        );
    }
}
