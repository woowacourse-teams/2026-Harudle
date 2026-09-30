package com.harudle.generation.adapter.out.gemini;

import com.google.genai.Models;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;
import com.harudle.common.logging.ExternalApiResponseDiagnostics;
import com.harudle.generation.config.GeminiGenerationProperties;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
import com.harudle.generation.diary.service.port.dto.GeneratedStoryboard;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import java.util.Optional;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

public final class GeminiStoryboardGenerator implements StoryboardGenerator {

    private static final String OPERATION = "storyboard_generation";
    private static final String TRANSLATION_OPERATION = "스토리보드 생성";
    private static final String REQUEST_PREPARATION_ERROR = "REQUEST_PREPARATION_ERROR";
    private static final String JSON_RESPONSE_MIME_TYPE = "application/json";
    private static final String DIARY_REQUEST_TEMPLATE = """
            <context>
            <diary>
            %s
            </diary>
            </context>

            <task>
            Based only on the diary above, create exactly one schema-compliant four-panel storyboard.
            Before returning, verify:
            - exactly four panels use setup → action → escalation → resolution in order
            - one source-faithful central cause-and-effect chain preserves the actual outcome
            - panel 3 contains the main turn and panel 4 completes or changes earlier meaning
            - the four captions form one connected verbal routine rather than four event labels
            - every valid character annotation is preserved consistently
            Return only the JSON.
            </task>""";

    private final Models models;
    private final GeminiGenerationProperties properties;
    private final ObjectMapper objectMapper;
    private final GeminiStoryboardResponseMapper responseMapper;
    private final GeminiFailureReporter failureReporter;
    private final GeminiStageMetrics stageMetrics;

    public GeminiStoryboardGenerator(
            Models models,
            GeminiGenerationProperties properties,
            ObjectMapper objectMapper,
            GeminiStoryboardResponseMapper responseMapper,
            GeminiFailureReporter failureReporter,
            GeminiStageMetrics stageMetrics
    ) {
        this.models = models;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.responseMapper = responseMapper;
        this.failureReporter = failureReporter;
        this.stageMetrics = Objects.requireNonNull(stageMetrics);
    }

    @Override
    public GeneratedStoryboard generate(StoryboardGenerationRequest request) {
        long startedAt = System.nanoTime();
        String phase = "preparation";
        GenerateContentResponse response = null;
        try {
            PreparedRequest preparedRequest = prepareRequest(request);
            phase = "provider";
            response = callProvider(preparedRequest);
            phase = "response";
            GeneratedStoryboard storyboard = processResponse(response);
            stageMetrics.record(OPERATION, null, System.nanoTime() - startedAt, response);
            return storyboard;
        } catch (com.harudle.generation.diary.service.exception.AiGenerationException exception) {
            GeminiFailureType failureType = switch (phase) {
                case "preparation" -> GeminiFailureType.preparation(exception.getCause());
                case "provider" -> GeminiFailureType.provider(exception.getCause(), exception.errorType());
                default -> GeminiFailureType.storyboardResponse(
                        GeminiFailureType.finishReason(response), exception.getCause());
            };
            stageMetrics.record(OPERATION, failureType, System.nanoTime() - startedAt, response);
            throw exception;
        }
    }

    private PreparedRequest prepareRequest(StoryboardGenerationRequest request) {
        try {
            String requestText = createRequestText(request);
            GenerateContentConfig config = createGenerateContentConfig(request.storyboardPromptText());
            return new PreparedRequest(requestText, config);
        } catch (Exception exception) {
            throw failureReporter.reportInternalFailure(
                    OPERATION,
                    TRANSLATION_OPERATION,
                    REQUEST_PREPARATION_ERROR,
                    exception
            );
        }
    }

    private GenerateContentResponse callProvider(PreparedRequest request) {
        try {
            return models.generateContent(
                    properties.storyboardModel(),
                    request.text(),
                    request.config()
            );
        } catch (Exception exception) {
            throw failureReporter.reportProviderFailure(OPERATION, TRANSLATION_OPERATION, exception);
        }
    }

    private GeneratedStoryboard processResponse(GenerateContentResponse response) {
        String responseText = null;
        try {
            if (response != null) {
                responseText = response.text();
            }
            Storyboard storyboard = mapResponse(response, responseText);
            return new GeneratedStoryboard(storyboard, tokenUsage(response));
        } catch (Exception exception) {
            throw failureReporter.reportStoryboardResponseFailure(
                    OPERATION,
                    TRANSLATION_OPERATION,
                    responseDiagnostics(response, responseText),
                    exception
            );
        }
    }

    private static GenerationTokenUsage tokenUsage(GenerateContentResponse response) {
        return response.usageMetadata()
                .map(metadata -> new GenerationTokenUsage(
                        metadata.promptTokenCount().orElse(null),
                        metadata.candidatesTokenCount().orElse(null),
                        metadata.thoughtsTokenCount().orElse(null),
                        metadata.totalTokenCount().orElse(null)
                ))
                .orElse(null);
    }

    private ExternalApiResponseDiagnostics responseDiagnostics(
            GenerateContentResponse response,
            String responseText
    ) {
        if (response == null) {
            return new ExternalApiResponseDiagnostics(
                    null, null, null, properties.maxOutputTokens(), null
            );
        }
        Integer candidateTokenCount = null;
        Integer thoughtTokenCount = null;
        try {
            Optional<GenerateContentResponseUsageMetadata> usageMetadata = response.usageMetadata();
            if (usageMetadata != null && usageMetadata.isPresent()) {
                candidateTokenCount = usageMetadata.get().candidatesTokenCount().orElse(null);
                thoughtTokenCount = usageMetadata.get().thoughtsTokenCount().orElse(null);
            }
        } catch (RuntimeException ignored) {
            // Response diagnostics must not replace the original response-processing failure.
        }
        return new ExternalApiResponseDiagnostics(
                GeminiFailureType.finishReason(response),
                candidateTokenCount,
                thoughtTokenCount,
                properties.maxOutputTokens(),
                responseText == null ? null : responseText.length()
        );
    }

    private record PreparedRequest(String text, GenerateContentConfig config) {
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

    private Storyboard mapResponse(
            GenerateContentResponse response,
            String responseText
    ) throws Exception {
        if (response == null) {
            throw new IllegalStateException("Gemini 스토리보드 응답이 없습니다.");
        }

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
