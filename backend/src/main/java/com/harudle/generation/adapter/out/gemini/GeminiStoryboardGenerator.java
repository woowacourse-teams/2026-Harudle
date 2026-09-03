package com.harudle.generation.adapter.out.gemini;

import com.google.genai.Models;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;
import com.harudle.generation.config.GeminiGenerationProperties;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import tools.jackson.databind.ObjectMapper;

public final class GeminiStoryboardGenerator implements StoryboardGenerator {

    private static final String OPERATION = "storyboard_generation";
    private static final String TRANSLATION_OPERATION = "스토리보드 생성";
    private static final String REQUEST_PREPARATION_ERROR = "REQUEST_PREPARATION_ERROR";
    private static final String RESPONSE_PROCESSING_ERROR = "RESPONSE_PROCESSING_ERROR";
    private static final String JSON_RESPONSE_MIME_TYPE = "application/json";
    private static final String DIARY_REQUEST_TEMPLATE = """
            <context>
            <diary>
            %s
            </diary>
            </context>

            <task>
            Based only on the diary above, create exactly one schema-compliant four-panel storyboard.
            Preserve its central cause-and-effect chain and actual outcome.
            Use setup → action → escalation → resolution.
            Make the four captions form one connected miniature comedy or warm emotional routine.
            Return only the JSON.
            </task>""";

    private final Models models;
    private final GeminiGenerationProperties properties;
    private final ObjectMapper objectMapper;
    private final GeminiStoryboardResponseMapper responseMapper;
    private final GeminiFailureReporter failureReporter;

    public GeminiStoryboardGenerator(
            Models models,
            GeminiGenerationProperties properties,
            ObjectMapper objectMapper,
            GeminiStoryboardResponseMapper responseMapper,
            GeminiFailureReporter failureReporter
    ) {
        this.models = models;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.responseMapper = responseMapper;
        this.failureReporter = failureReporter;
    }

    @Override
    public Storyboard generate(StoryboardGenerationRequest request) {
        String requestText;
        GenerateContentConfig config;
        try {
            requestText = createRequestText(request);
            config = createGenerateContentConfig(request.storyboardPromptText());
        } catch (Exception exception) {
            throw failureReporter.reportInternalFailure(
                    OPERATION,
                    TRANSLATION_OPERATION,
                    REQUEST_PREPARATION_ERROR,
                    exception
            );
        }

        GenerateContentResponse response;
        try {
            response = models.generateContent(
                    properties.storyboardModel(),
                    requestText,
                    config
            );
        } catch (Exception exception) {
            throw failureReporter.reportProviderFailure(OPERATION, TRANSLATION_OPERATION, exception);
        }

        try {
            return mapResponse(response);
        } catch (Exception exception) {
            throw failureReporter.reportInternalFailure(
                    OPERATION,
                    TRANSLATION_OPERATION,
                    RESPONSE_PROCESSING_ERROR,
                    exception
            );
        }
    }

    private static String createRequestText(StoryboardGenerationRequest request) {
        return DIARY_REQUEST_TEMPLATE.formatted(request.diaryText());
    }

    private GenerateContentConfig createGenerateContentConfig(String systemPrompt) {
        ThinkingConfig thinkingConfig = ThinkingConfig.builder()
                .thinkingLevel(properties.storyboardThinkingLevel())
                .build();

        return GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(systemPrompt)))
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
