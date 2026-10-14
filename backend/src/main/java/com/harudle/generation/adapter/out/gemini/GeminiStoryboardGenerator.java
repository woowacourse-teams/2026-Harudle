package com.harudle.generation.adapter.out.gemini;

import com.google.genai.Models;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;
import com.harudle.common.logging.ExternalApiResponseDiagnostics;
import com.harudle.generation.adapter.out.gemini.client.GeminiStoryboardCallScope;
import com.harudle.generation.adapter.out.storyboard.StoryboardJsonCodec;
import com.harudle.generation.adapter.out.storyboard.StoryboardRequestRenderer;
import com.harudle.generation.adapter.out.storyboard.StoryboardResponseMapper;
import com.harudle.generation.config.GeminiGenerationProperties;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.domain.StoryboardProviderInfo;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.exception.AiProviderFailureType;
import com.harudle.generation.diary.service.port.StoryboardAttemptContext;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import com.harudle.generation.diary.service.port.dto.GeneratedStoryboard;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

public final class GeminiStoryboardGenerator implements StoryboardGenerator {

    private static final String OPERATION = "storyboard_generation";
    private static final String TRANSLATION_OPERATION = "스토리보드 생성";
    private static final String REQUEST_PREPARATION_ERROR = "REQUEST_PREPARATION_ERROR";
    private static final String JSON_RESPONSE_MIME_TYPE = "application/json";

    private final Models models;
    private final GeminiGenerationProperties properties;
    private final StoryboardJsonCodec codec;
    private final GeminiFailureReporter failureReporter;
    private final GeminiStageMetrics stageMetrics;
    private final GeminiStoryboardCallScope callScope;

    public GeminiStoryboardGenerator(
            Models models,
            GeminiGenerationProperties properties,
            ObjectMapper objectMapper,
            StoryboardResponseMapper responseMapper,
            GeminiFailureReporter failureReporter,
            GeminiStageMetrics stageMetrics,
            GeminiStoryboardCallScope callScope
    ) {
        this.models = models;
        this.properties = properties;
        this.codec = new StoryboardJsonCodec(objectMapper, responseMapper);
        this.failureReporter = failureReporter;
        this.stageMetrics = Objects.requireNonNull(stageMetrics);
        this.callScope = Objects.requireNonNull(callScope);
    }

    @Override
    public StoryboardProviderInfo providerInfo() {
        return new StoryboardProviderInfo("gemini", properties.storyboardModel());
    }

    @Override
    public GeneratedStoryboard generate(StoryboardGenerationRequest request) {
        long startedAt = System.nanoTime();
        String phase = "preparation";
        GenerateContentResponse response = null;
        Optional<StoryboardAttemptContext> context = request.attemptContext();
        try {
            checkActive(context);
            PreparedRequest preparedRequest = prepareRequest(request);
            checkActive(context);
            phase = "provider";
            response = callProvider(preparedRequest, context);
            phase = "response";
            GeneratedStoryboard storyboard = processResponse(response, context);
            stageMetrics.record(OPERATION, null, System.nanoTime() - startedAt, response);
            return storyboard;
        } catch (AiGenerationException exception) {
            GeminiFailureType failureType = failureType(phase, response, exception);
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
            checkActive(request.attemptContext());
            throw failureReporter.reportInternalFailure(
                    OPERATION,
                    TRANSLATION_OPERATION,
                    REQUEST_PREPARATION_ERROR,
                    AiGenerationErrorType.INTERNAL_ERROR,
                    exception
            );
        }
    }

    private GenerateContentResponse callProvider(
            PreparedRequest request,
            Optional<StoryboardAttemptContext> context
    ) {
        try {
            checkActive(context);
            if (context.isPresent()) {
                StoryboardAttemptContext attempt = context.orElseThrow();
                GenerateContentConfig config = request.config().toBuilder()
                        .httpOptions(remainingHttpOptions(attempt))
                        .build();
                return callScope.withContext(attempt, () ->
                        models.generateContent(properties.storyboardModel(), request.text(), config));
            }
            return models.generateContent(properties.storyboardModel(), request.text(), request.config());
        } catch (AiGenerationException exception) {
            throw exception;
        } catch (Exception exception) {
            checkActive(context);
            throw failureReporter.reportProviderFailure(OPERATION, TRANSLATION_OPERATION, exception);
        }
    }

    private GeneratedStoryboard processResponse(
            GenerateContentResponse response,
            Optional<StoryboardAttemptContext> context
    ) {
        String responseText = null;
        try {
            checkActive(context);
            if (response != null) {
                responseText = response.text();
            }
            Storyboard storyboard = mapResponse(response, responseText);
            checkActive(context);
            return new GeneratedStoryboard(storyboard, tokenUsage(response), providerInfo());
        } catch (AiGenerationException exception) {
            throw exception;
        } catch (Exception exception) {
            checkActive(context);
            throw failureReporter.reportStoryboardResponseFailure(
                    OPERATION,
                    TRANSLATION_OPERATION,
                    responseDiagnostics(response, responseText),
                    exception
            );
        }
    }

    private static void checkActive(Optional<StoryboardAttemptContext> context) {
        context.ifPresent(StoryboardAttemptContext::checkActive);
    }

    private static HttpOptions remainingHttpOptions(StoryboardAttemptContext context) {
        context.checkActive();
        long remainingNanos = context.remainingTime().toNanos();
        int timeoutMillis = Math.toIntExact(Math.max(1, (remainingNanos + 999_999) / 1_000_000));
        return HttpOptions.builder().timeout(timeoutMillis)
                .retryOptions(HttpRetryOptions.builder().attempts(1).httpStatusCodes(List.of()).build())
                .build();
    }

    private static GeminiFailureType failureType(
            String phase,
            GenerateContentResponse response,
            AiGenerationException exception
    ) {
        if (exception.errorType() == AiGenerationErrorType.TIMEOUT) {
            return GeminiFailureType.TIMEOUT;
        }
        if (exception.providerFailureType() == AiProviderFailureType.CANCELLED) {
            return GeminiFailureType.CANCELLED;
        }
        return switch (phase) {
            case "preparation" -> GeminiFailureType.preparation(exception.getCause());
            case "provider" -> GeminiFailureType.provider(exception.getCause(), exception.errorType());
            default -> GeminiFailureType.storyboardResponse(
                    GeminiFailureType.finishReason(response), exception.getCause());
        };
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
        return StoryboardRequestRenderer.render(request);
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

        return codec.decode(responseText);
    }
}
