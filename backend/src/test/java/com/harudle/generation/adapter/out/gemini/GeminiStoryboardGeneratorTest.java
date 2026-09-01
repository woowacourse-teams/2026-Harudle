package com.harudle.generation.adapter.out.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.genai.Models;
import com.google.genai.errors.ClientException;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.ThinkingLevel;
import com.harudle.common.logging.ExternalApiFailure;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.config.GeminiGenerationProperties;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class GeminiStoryboardGeneratorTest {

    private final Models models = mock(Models.class);
    private final GenerateContentResponse response = mock(GenerateContentResponse.class);
    private final ExternalApiLogger externalApiLogger = mock(ExternalApiLogger.class);
    private final GeminiGenerationProperties properties = createProperties();
    private final GeminiStoryboardGenerator generator = new GeminiStoryboardGenerator(
            models,
            properties,
            JsonMapper.builder().build(),
            new GeminiStoryboardResponseMapper(),
            new GeminiFailureReporter(
                    new GeminiExceptionTranslator(),
                    externalApiLogger
            )
    );

    @BeforeEach
    void setUp() {
        when(models.generateContent(anyString(), anyString(), any(GenerateContentConfig.class)))
                .thenReturn(response);
    }

    @Test
    @DisplayName("일기와 프롬프트로 Gemini 스토리보드를 생성한다")
    void generateStoryboard() {
        when(response.text()).thenReturn(validResponseJson());
        StoryboardGenerationRequest request = new StoryboardGenerationRequest(
                "오늘 친구와 카페에 갔다.",
                "스토리보드 생성 규칙"
        );

        Storyboard storyboard = generator.generate(request);

        assertThat(storyboard.title()).isEqualTo("카페에서 생긴 일");
        assertThat(storyboard.panels()).hasSize(4);

        ArgumentCaptor<String> requestTextCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<GenerateContentConfig> configCaptor = ArgumentCaptor.forClass(GenerateContentConfig.class);
        verify(models).generateContent(
                eq("gemini-3.5-flash-lite"),
                requestTextCaptor.capture(),
                configCaptor.capture()
        );

        assertThat(requestTextCaptor.getValue()).isEqualTo("""
                스토리보드 생성 규칙
                
                [SOURCE DIARY — preserve its meaning]
                오늘 친구와 카페에 갔다.""");

        GenerateContentConfig config = configCaptor.getValue();
        assertThat(config.responseMimeType()).contains("application/json");
        assertThat(config.responseJsonSchema())
                .contains(GeminiStoryboardResponseSchema.schema());
        assertThat(config.maxOutputTokens()).contains(4096);
        assertThat(config.candidateCount()).contains(1);
        assertThat(config.thinkingConfig()).get()
                .extracting(thinkingConfig -> thinkingConfig.thinkingLevel().orElseThrow().knownEnum())
                .isEqualTo(ThinkingLevel.Known.HIGH);
        verifyNoInteractions(externalApiLogger);
    }

    @Test
    @DisplayName("Gemini 응답 본문이 비어 있으면 제공자 오류가 발생한다")
    void rejectEmptyResponse() {
        when(response.text()).thenReturn(" ");

        assertThatThrownBy(() -> generator.generate(createRequest()))
                .isInstanceOfSatisfying(AiGenerationException.class, exception -> {
                    assertThat(exception.errorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
                    assertThat(exception.getCause()).isInstanceOf(IllegalStateException.class);
                });
        verify(externalApiLogger).error(
                eq(new ExternalApiFailure(
                        "gemini",
                        "storyboard_generation",
                        "RESPONSE_PROCESSING_ERROR",
                        null,
                        null,
                        null
                )),
                any(IllegalStateException.class)
        );
    }

    @Test
    @DisplayName("Gemini 응답 JSON이 올바르지 않으면 제공자 오류가 발생한다")
    void rejectInvalidResponseJson() {
        when(response.text()).thenReturn("{invalid-json}");

        assertThatThrownBy(() -> generator.generate(createRequest()))
                .isInstanceOfSatisfying(
                        AiGenerationException.class,
                        exception -> assertThat(exception.errorType())
                                .isEqualTo(AiGenerationErrorType.PROVIDER_ERROR)
                );
    }

    @Test
    @DisplayName("Gemini 응답이 스토리보드 규칙을 위반하면 제공자 오류가 발생한다")
    void rejectInvalidStoryboardResponse() {
        when(response.text()).thenReturn(validResponseJson().replace(
                "\"story_role\": \"action\"",
                "\"story_role\": \"resolution\""
        ));

        assertThatThrownBy(() -> generator.generate(createRequest()))
                .isInstanceOfSatisfying(AiGenerationException.class, exception -> {
                    assertThat(exception.errorType()).isEqualTo(AiGenerationErrorType.PROVIDER_ERROR);
                    assertThat(exception.getCause()).isInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    @DisplayName("Gemini 요청 시간이 초과되면 시간 초과 오류가 발생한다")
    void translateTimeoutException() {
        ClientException cause = new ClientException(408, "REQUEST_TIMEOUT", "timeout");
        when(models.generateContent(anyString(), anyString(), any(GenerateContentConfig.class)))
                .thenThrow(cause);

        assertThatThrownBy(() -> generator.generate(createRequest()))
                .isInstanceOfSatisfying(AiGenerationException.class, exception -> {
                    assertThat(exception.errorType()).isEqualTo(AiGenerationErrorType.TIMEOUT);
                    assertThat(exception).hasCause(cause);
                });
        verify(externalApiLogger).warn(
                new ExternalApiFailure(
                        "gemini",
                        "storyboard_generation",
                        "TIMEOUT",
                        "REQUEST_TIMEOUT",
                        "408",
                        null
                ),
                cause
        );
    }

    private StoryboardGenerationRequest createRequest() {
        return new StoryboardGenerationRequest("오늘 친구와 카페에 갔다.", "스토리보드 생성 규칙");
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

    private static String validResponseJson() {
        return """
                {
                  "title": "카페에서 생긴 일",
                  "cast_continuity": "One recurring protagonist in casual clothes.",
                  "panels": [
                    {
                      "panel_number": 1,
                      "story_role": "setup",
                      "caption": "카페에 도착",
                      "scene": "A person enters a cafe.",
                      "characters": "The protagonist stands by the entrance.",
                      "emotion": "Visible excitement.",
                      "props": ["door"]
                    },
                    {
                      "panel_number": 2,
                      "story_role": "action",
                      "caption": "메뉴를 고르고",
                      "scene": "The person studies the menu.",
                      "characters": "The protagonist sits at a table.",
                      "emotion": "Focused anticipation.",
                      "props": ["menu"]
                    },
                    {
                      "panel_number": 3,
                      "story_role": "escalation",
                      "caption": "친구가 웃더니",
                      "scene": "A friend points at the drink.",
                      "characters": "Two friends sit together.",
                      "emotion": "Visible amusement.",
                      "props": ["cup"]
                    },
                    {
                      "panel_number": 4,
                      "story_role": "resolution",
                      "caption": "나도 웃었다",
                      "scene": "Both friends laugh together.",
                      "characters": "The friends lean toward each other.",
                      "emotion": "Shared laughter.",
                      "props": []
                    }
                  ]
                }
                """;
    }
}
