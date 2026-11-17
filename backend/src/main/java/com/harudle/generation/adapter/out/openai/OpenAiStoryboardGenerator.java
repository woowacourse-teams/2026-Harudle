package com.harudle.generation.adapter.out.openai;

import com.harudle.common.logging.ExternalApiFailure;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.adapter.out.storyboard.StoryboardJsonCodec;
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
import java.util.Objects;

public final class OpenAiStoryboardGenerator implements StoryboardGenerator {

    private static final String PROVIDER = "openai";
    private static final String OPERATION = "storyboard_generation";

    private final OpenAiChatClient client;
    private final OpenAiStoryboardSettings settings;
    private final OpenAiStoryboardRequestFactory requestFactory;
    private final StoryboardJsonCodec codec;
    private final ExternalApiLogger logger;
    private final OpenAiStageMetrics stageMetrics;

    public OpenAiStoryboardGenerator(OpenAiChatClient client, OpenAiStoryboardSettings settings,
            StoryboardJsonCodec codec, ExternalApiLogger logger, OpenAiStageMetrics stageMetrics) {
        this.client = Objects.requireNonNull(client);
        this.settings = Objects.requireNonNull(settings);
        this.codec = Objects.requireNonNull(codec);
        this.logger = Objects.requireNonNull(logger);
        this.stageMetrics = Objects.requireNonNull(stageMetrics);
        this.requestFactory = new OpenAiStoryboardRequestFactory(settings, codec);
    }

    @Override
    public StoryboardProviderInfo providerInfo() {
        return new StoryboardProviderInfo(PROVIDER, settings.model());
    }

    @Override
    public GeneratedStoryboard generate(StoryboardGenerationRequest request) {
        long started = System.nanoTime();
        String phase = "preparation";
        AiGenerationErrorType failureType = null;
        OpenAiChatClient.Completion result = null;
        GenerationTokenUsage usage = null;
        try {
            request.attemptContext().ifPresent(StoryboardAttemptContext::checkActive);
            OpenAiChatClient.Request prepared = requestFactory.create(request);
            phase = "provider";
            result = client.generate(prepared, request.attemptContext());
            phase = "response";
            request.attemptContext().ifPresent(StoryboardAttemptContext::checkActive);
            usage = tokenUsage(result.usage());
            Storyboard storyboard = codec.decode(result.content());
            request.attemptContext().ifPresent(StoryboardAttemptContext::checkActive);
            return new GeneratedStoryboard(storyboard, usage, providerInfo());
        } catch (AiGenerationException exception) {
            failureType = exception.errorType();
            throw exception;
        } catch (OpenAiChatException exception) {
            failureType = resolveErrorType(exception);
            throw report(failureType, exception.statusCode(), resolveProviderFailureType(exception, failureType));
        } catch (RuntimeException exception) {
            AiGenerationException failure = translateLocalFailure(request, phase);
            failureType = failure.errorType();
            throw failure;
        } finally {
            stageMetrics.record(failureType, System.nanoTime() - started, result, usage);
        }
    }

    private AiGenerationException translateLocalFailure(StoryboardGenerationRequest request, String phase) {
        try {
            request.attemptContext().ifPresent(StoryboardAttemptContext::checkActive);
        } catch (AiGenerationException exception) {
            return exception;
        }
        AiGenerationErrorType errorType = "response".equals(phase)
                ? AiGenerationErrorType.RESPONSE_PROCESSING_ERROR : AiGenerationErrorType.INTERNAL_ERROR;
        return report(errorType, null, AiProviderFailureType.UNKNOWN);
    }

    /** OpenAI의 completion_tokens에는 추론 토큰이 포함되므로 최종 답변 토큰은 그 차이로 계산한다. */
    public static GenerationTokenUsage tokenUsage(OpenAiChatClient.Usage usage) {
        if (usage == null) {
            return null;
        }
        Integer candidateTokens = null;
        Integer thoughts = usage.reasoningTokens();
        if (usage.completionTokens() != null && thoughts != null && thoughts <= usage.completionTokens()) {
            candidateTokens = usage.completionTokens() - thoughts;
        }
        return new GenerationTokenUsage(usage.promptTokens(), candidateTokens, thoughts, usage.totalTokens());
    }

    private AiGenerationException report(
            AiGenerationErrorType type,
            Integer statusCode,
            AiProviderFailureType providerFailureType
    ) {
        AiGenerationException failure = new AiGenerationException(type, "OpenAI 스토리보드 생성에 실패했습니다.",
                null, providerFailureType);
        ExternalApiFailure metadata = new ExternalApiFailure(PROVIDER, OPERATION, type.name(),
                statusCode == null ? null : statusCode.toString(), null, null);
        if (type == AiGenerationErrorType.RESPONSE_PROCESSING_ERROR || type == AiGenerationErrorType.INTERNAL_ERROR) {
            logger.error(metadata, failure);
        } else {
            logger.warn(metadata, failure);
        }
        return failure;
    }

    private static AiGenerationErrorType resolveErrorType(OpenAiChatException exception) {
        return switch (exception.kind()) {
            case CONTENT_BLOCKED -> AiGenerationErrorType.CONTENT_BLOCKED;
            case TIMEOUT -> AiGenerationErrorType.TIMEOUT;
            case NETWORK -> AiGenerationErrorType.PROVIDER_ERROR;
            case RESPONSE -> AiGenerationErrorType.RESPONSE_PROCESSING_ERROR;
            case TRUNCATED -> AiGenerationErrorType.OUTPUT_TRUNCATED;
            case HTTP -> httpErrorType(exception.statusCode());
        };
    }

    private static AiProviderFailureType resolveProviderFailureType(
            OpenAiChatException exception,
            AiGenerationErrorType errorType
    ) {
        if ((exception.kind() == OpenAiChatException.Kind.NETWORK
                || exception.kind() == OpenAiChatException.Kind.TIMEOUT)
                && Thread.currentThread().isInterrupted()) {
            return AiProviderFailureType.CANCELLED;
        }
        if (errorType != AiGenerationErrorType.PROVIDER_ERROR) {
            return AiProviderFailureType.UNKNOWN;
        }
        if (exception.kind() == OpenAiChatException.Kind.NETWORK) {
            return AiProviderFailureType.NETWORK_ERROR;
        }
        return httpFailureType(exception.statusCode());
    }

    private static AiProviderFailureType httpFailureType(Integer statusCode) {
        if (statusCode == null) {
            return AiProviderFailureType.UNKNOWN;
        }
        if (statusCode == 401 || statusCode == 403) {
            return AiProviderFailureType.AUTH_CONFIGURATION_ERROR;
        }
        if (statusCode == 500 || statusCode == 502 || statusCode == 503) {
            return AiProviderFailureType.TRANSIENT_SERVER_ERROR;
        }
        if (statusCode >= 400 && statusCode < 500) {
            return AiProviderFailureType.REQUEST_REJECTED;
        }
        return AiProviderFailureType.UNKNOWN;
    }

    private static AiGenerationErrorType httpErrorType(Integer statusCode) {
        if (Integer.valueOf(429).equals(statusCode)) {
            return AiGenerationErrorType.RATE_LIMITED;
        }
        if (Integer.valueOf(408).equals(statusCode) || Integer.valueOf(504).equals(statusCode)) {
            return AiGenerationErrorType.TIMEOUT;
        }
        return AiGenerationErrorType.PROVIDER_ERROR;
    }
}
