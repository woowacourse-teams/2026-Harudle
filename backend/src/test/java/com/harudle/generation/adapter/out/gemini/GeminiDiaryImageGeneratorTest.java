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
import com.google.genai.types.Candidate;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponsePromptFeedback;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.harudle.common.logging.ExternalApiFailure;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.common.logging.ExternalApiResponseDiagnostics;
import com.harudle.generation.config.GeminiGenerationProperties;
import com.harudle.generation.diary.domain.FocalColor;
import com.harudle.generation.diary.domain.ScenePlan;
import com.harudle.generation.diary.domain.StoryPanel;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.port.dto.DiaryImageGenerationRequest;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

class GeminiDiaryImageGeneratorTest {

    private final Models models = mock(Models.class);
    private final GenerateContentResponse response = mock(GenerateContentResponse.class);
    private final DiaryImagePromptRenderer promptRenderer = new DiaryImagePromptRenderer();
    private final ExternalApiLogger externalApiLogger = mock(ExternalApiLogger.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final GeminiDiaryImageGenerator generator = new GeminiDiaryImageGenerator(
            models,
            createProperties(),
            promptRenderer,
            new GeminiFailureReporter(
                    new GeminiExceptionTranslator(),
                    externalApiLogger
            ),
            new GeminiStageMetrics(meterRegistry)
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
                .startsWith("[Final Task]\nSELECTED STORY:")
                .doesNotContain("IMAGE STYLE PROMPT")
                .contains("ENVIRONMENT FOR THIS STORY:\nKeep the same cafe across all panels.")
                .contains("FOCAL COLOR SUGGESTIONS — NOT VISIBLE TEXT:\n"
                        + "- Panel 2: fill the entire visible cover of the existing Prop 2 "
                        + "with uniform opaque #A99BE8; keep the same color in every appearance.")
                .contains("Panel 1 — TOP LEFT — SETUP:")
                .contains("Caption reads exactly: \"캡션 1\"")
                .contains("This panel contains exactly one readable text block: its assigned caption.")
                .contains("Render exactly six readable text blocks:")
                .contains("FINAL FOOTER LOCK — HIGHEST LAYOUT PRIORITY:")
                .contains("- left: \"# 카페에서 생긴 일\"")
                .contains("- right: \"@harudle.official\"");

        GenerateContentConfig config = configCaptor.getValue();
        assertThat(config.systemInstruction())
                .contains(Content.fromParts(Part.fromText("IMAGE STYLE PROMPT")));
        assertThat(config.responseModalities()).contains(List.of("TEXT", "IMAGE"));
        assertThat(config.imageConfig()).get()
                .extracting(imageConfig -> imageConfig.aspectRatio().orElseThrow())
                .isEqualTo("1:1");
        verifyNoInteractions(externalApiLogger);
        assertThat(meterRegistry.get("harudle.gemini.stage.calls")
                .tags("stage", "image_generation", "outcome", "success", "failureType", "none")
                .counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Gemini 응답에 이미지가 없으면 응답 처리 오류가 발생한다")
    void rejectResponseWithoutImage() {
        when(response.parts()).thenReturn(ImmutableList.of(Part.fromText("no image")));

        assertThatThrownBy(() -> generator.generate(createRequest(createReferenceImage())))
                .isInstanceOfSatisfying(AiGenerationException.class, exception -> {
                    assertThat(exception.errorType()).isEqualTo(AiGenerationErrorType.RESPONSE_PROCESSING_ERROR);
                    assertThat(exception.getCause()).isInstanceOf(IllegalStateException.class);
                });
        verify(externalApiLogger).error(
                eq(new ExternalApiFailure(
                        "gemini",
                        "image_generation",
                        "IMAGE_PART_MISSING",
                        null,
                        null,
                        null
                )),
                any(IllegalStateException.class),
                eq(new ExternalApiResponseDiagnostics(null, null, null, null, null))
        );
        assertThat(meterRegistry.get("harudle.gemini.stage.calls")
                .tags("stage", "image_generation", "outcome", "failure",
                        "failureType", "IMAGE_PART_MISSING")
                .counter().count()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("invalidImageParts")
    @DisplayName("Gemini 응답 이미지가 유효하지 않으면 응답 처리 오류가 발생한다")
    void rejectInvalidGeneratedImage(Part invalidImagePart) {
        when(response.parts()).thenReturn(ImmutableList.of(invalidImagePart));

        assertThatThrownBy(() -> generator.generate(createRequest(createReferenceImage())))
                .isInstanceOfSatisfying(
                        AiGenerationException.class,
                        exception -> assertThat(exception.errorType())
                                .isEqualTo(AiGenerationErrorType.RESPONSE_PROCESSING_ERROR)
                );
    }

    @ParameterizedTest
    @ValueSource(strings = {"SAFETY", "IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT", "IMAGE_RECITATION"})
    @DisplayName("이미지 생성 차단은 이미지 추출 전에 판별하고 처리 오류와 구분한다")
    void rejectBlockedImage(String reason) {
        when(response.candidates()).thenReturn(Optional.of(List.of(
                Candidate.builder().finishReason(new FinishReason(reason)).build())));

        assertThatThrownBy(() -> generator.generate(createRequest(createReferenceImage())))
                .isInstanceOfSatisfying(AiGenerationException.class,
                        failure -> assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.CONTENT_BLOCKED));
        verify(response, never()).parts();
        verify(externalApiLogger).warn(eq(new ExternalApiFailure(
                "gemini", "image_generation", "CONTENT_BLOCKED", null, null, null)),
                any(IllegalStateException.class), any(ExternalApiResponseDiagnostics.class));
        assertThat(meterRegistry.get("harudle.gemini.stage.calls")
                .tags("stage", "image_generation", "outcome", "failure", "failureType", "CONTENT_BLOCKED")
                .counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미지 프롬프트 차단도 후보나 이미지가 없는 일반 응답과 구분한다")
    void rejectBlockedImagePrompt() {
        when(response.promptFeedback()).thenReturn(Optional.of(
                GenerateContentResponsePromptFeedback.fromJson("{\"blockReason\":\"SAFETY\"}")));

        assertThatThrownBy(() -> generator.generate(createRequest(createReferenceImage())))
                .isInstanceOfSatisfying(AiGenerationException.class,
                        failure -> assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.CONTENT_BLOCKED));
        verify(response, never()).parts();
    }

    @Test
    @DisplayName("이미지 출력 토큰 한도 도달도 생성 결과 잘림으로 분류한다")
    void rejectTruncatedImage() {
        when(response.candidates()).thenReturn(Optional.of(List.of(
                Candidate.builder().finishReason(new FinishReason("MAX_TOKENS")).build())));
        when(response.parts()).thenReturn(ImmutableList.of(Part.fromText("unfinished image")));

        assertThatThrownBy(() -> generator.generate(createRequest(createReferenceImage())))
                .isInstanceOfSatisfying(AiGenerationException.class,
                        failure -> assertThat(failure.errorType()).isEqualTo(AiGenerationErrorType.OUTPUT_TRUNCATED));
        assertThat(meterRegistry.get("harudle.gemini.stage.calls")
                .tags("stage", "image_generation", "outcome", "failure", "failureType", "OUTPUT_TOKEN_LIMIT")
                .counter().count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OTHER", "IMAGE_OTHER", "NO_IMAGE", "FUTURE_REASON"})
    @DisplayName("이미지가 없거나 종료 사유가 불명확한 경우에는 콘텐츠 차단으로 단정하지 않는다")
    void preserveUnknownImageFailure(String reason) {
        when(response.candidates()).thenReturn(Optional.of(List.of(
                Candidate.builder().finishReason(new FinishReason(reason)).build())));
        when(response.parts()).thenReturn(ImmutableList.of(Part.fromText("no image")));

        assertThatThrownBy(() -> generator.generate(createRequest(createReferenceImage())))
                .isInstanceOfSatisfying(AiGenerationException.class,
                        failure -> assertThat(failure.errorType())
                                .isEqualTo(AiGenerationErrorType.RESPONSE_PROCESSING_ERROR));
    }

    @Test
    @DisplayName("차단 사유가 지정되지 않은 피드백만으로 생성 차단을 판단하지 않는다")
    void preserveUnspecifiedPromptFailure() {
        when(response.promptFeedback()).thenReturn(Optional.of(GenerateContentResponsePromptFeedback.fromJson(
                "{\"blockReason\":\"BLOCKED_REASON_UNSPECIFIED\"}")));
        when(response.parts()).thenReturn(ImmutableList.of(Part.fromText("no image")));

        assertThatThrownBy(() -> generator.generate(createRequest(createReferenceImage())))
                .isInstanceOfSatisfying(AiGenerationException.class,
                        failure -> assertThat(failure.errorType())
                                .isEqualTo(AiGenerationErrorType.RESPONSE_PROCESSING_ERROR));
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
                    assertThat(exception.errorType()).isEqualTo(AiGenerationErrorType.INTERNAL_ERROR);
                    assertThat(exception.getCause()).isInstanceOf(IllegalArgumentException.class);
                });
        verify(models, never()).generateContent(anyString(), any(Content.class), any(GenerateContentConfig.class));
        verify(externalApiLogger).error(
                eq(new ExternalApiFailure(
                        "gemini",
                        "image_generation",
                        "INLINE_REQUEST_TOO_LARGE",
                        null,
                        null,
                        null
                )),
                any(IllegalArgumentException.class)
        );
        assertThat(meterRegistry.get("harudle.gemini.stage.calls")
                .tags("stage", "image_generation", "outcome", "failure",
                        "failureType", "INLINE_REQUEST_TOO_LARGE")
                .counter().count()).isEqualTo(1);
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

    @Test
    @DisplayName("이미지 단계의 공급자 토큰 사용량은 전체 생성 성공 여부와 독립적으로 집계한다")
    void recordImageStageTokenUsage() {
        when(response.parts()).thenReturn(ImmutableList.of(Part.fromBytes("image".getBytes(), "image/png")));
        GenerateContentResponseUsageMetadata usage = mock(GenerateContentResponseUsageMetadata.class);
        when(usage.promptTokenCount()).thenReturn(Optional.of(250));
        when(usage.totalTokenCount()).thenReturn(Optional.of(900));
        when(response.usageMetadata()).thenReturn(Optional.of(usage));

        generator.generate(createRequest(createReferenceImage()));

        assertThat(meterRegistry.get("harudle.gemini.tokens")
                .tags("stage", "image_generation", "kind", "prompt")
                .counter().count()).isEqualTo(250);
        assertThat(meterRegistry.get("harudle.gemini.tokens")
                .tags("stage", "image_generation", "kind", "total")
                .counter().count()).isEqualTo(900);
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
                ),
                new ScenePlan(
                        "Keep the same cafe across all panels.",
                        List.of(new FocalColor("Prop 2", "cover", "#A99BE8", List.of(2)))
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
