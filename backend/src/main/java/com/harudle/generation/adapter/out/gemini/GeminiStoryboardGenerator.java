package com.harudle.generation.adapter.out.gemini;

import com.google.genai.Models;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.ThinkingConfig;
import com.harudle.generation.configuration.GeminiGenerationProperties;
import com.harudle.generation.domain.Storyboard;
import com.harudle.generation.service.port.StoryboardGenerationRequest;
import com.harudle.generation.service.port.StoryboardGenerator;
import tools.jackson.databind.ObjectMapper;

public final class GeminiStoryboardGenerator implements StoryboardGenerator {

    private static final String OPERATION = "스토리보드 생성";
    private static final String SOURCE_DIARY_HEADER = "[SOURCE DIARY — preserve its meaning]";
    private static final String JSON_RESPONSE_MIME_TYPE = "application/json";

    private final Models models;
    private final GeminiGenerationProperties properties;
    private final ObjectMapper objectMapper;
    private final GeminiStoryboardResponseMapper responseMapper;
    private final GeminiExceptionTranslator exceptionTranslator;

    public GeminiStoryboardGenerator(
            Models models,
            GeminiGenerationProperties properties,
            ObjectMapper objectMapper,
            GeminiStoryboardResponseMapper responseMapper,
            GeminiExceptionTranslator exceptionTranslator
    ) {
        this.models = models;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.responseMapper = responseMapper;
        this.exceptionTranslator = exceptionTranslator;
    }

    @Override
    public Storyboard generate(StoryboardGenerationRequest request) {
        try {
            String requestText = createRequestText(request);
            GenerateContentConfig config = createGenerateContentConfig();
            GenerateContentResponse response = models.generateContent(
                    properties.storyboardModel(),
                    requestText,
                    config
            );
            return mapResponse(response);
        } catch (Exception exception) {
            throw exceptionTranslator.translate(OPERATION, exception);
        }
    }

    private static String createRequestText(StoryboardGenerationRequest request) {
        return request.storyboardPromptText()
                + "\n\n"
                + SOURCE_DIARY_HEADER
                + "\n"
                + request.diaryText();
    }

    private GenerateContentConfig createGenerateContentConfig() {
        ThinkingConfig thinkingConfig = ThinkingConfig.builder()
                .thinkingLevel(properties.storyboardThinkingLevel())
                .build();

        return GenerateContentConfig.builder()
                .responseMimeType(JSON_RESPONSE_MIME_TYPE)
                .responseJsonSchema(GeminiStoryboardResponseSchema.schema())
                .maxOutputTokens(properties.maxOutputTokens())
                .thinkingConfig(thinkingConfig)
                .candidateCount(1)
                .build();
    }

    private Storyboard mapResponse(GenerateContentResponse response) throws Exception {
        if (response == null) {
            throw new IllegalStateException("Gemini 스토리보드 응답이 없습니다.");
        }

        String responseText = response.text();
        if (responseText == null || responseText.isBlank()) {
            throw new IllegalStateException("Gemini 스토리보드 응답 본문이 비어 있습니다.");
        }

        GeminiStoryboardResponse storyboardResponse = objectMapper.readValue(
                responseText,
                GeminiStoryboardResponse.class
        );
        return responseMapper.map(storyboardResponse);
    }
}
