package com.harudle.generation.adapter.out.qwen;

import com.harudle.common.logging.ExternalApiFailure;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.adapter.out.storyboard.StoryboardJsonCodec;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.exception.AiProviderFailureType;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import com.harudle.generation.diary.service.port.dto.GeneratedStoryboard;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import java.util.Objects;

public final class QwenStoryboardGenerator implements StoryboardGenerator {

    private static final String OPERATION = "storyboard_generation";

    private final QwenChatClient client;
    private final QwenStoryboardSettings settings;
    private final QwenStoryboardRequestFactory requestFactory;
    private final StoryboardJsonCodec codec;
    private final ExternalApiLogger logger;
    private final QwenStageMetrics stageMetrics;

    public QwenStoryboardGenerator(QwenChatClient client, QwenStoryboardSettings settings,
            StoryboardJsonCodec codec, ExternalApiLogger logger, QwenStageMetrics stageMetrics) {
        this.client = Objects.requireNonNull(client);
        this.settings = Objects.requireNonNull(settings);
        this.codec = Objects.requireNonNull(codec);
        this.logger = Objects.requireNonNull(logger);
        this.stageMetrics = Objects.requireNonNull(stageMetrics);
        this.requestFactory = new QwenStoryboardRequestFactory(settings, codec);
    }

    @Override
    public GeneratedStoryboard generate(StoryboardGenerationRequest request) {
        long started = System.nanoTime();
        String phase = "preparation";
        AiGenerationErrorType failureType = null;
        QwenChatClient.Completion result = null;
        GenerationTokenUsage usage = null;
        try {
            QwenChatClient.Request prepared = requestFactory.create(request);
            phase = "provider";
            result = client.generate(prepared);
            phase = "response";
            usage = tokenUsage(result.usage(), settings.thinking());
            Storyboard storyboard = codec.decode(result.content());
            return new GeneratedStoryboard(storyboard, usage);
        } catch (QwenChatException exception) {
            failureType = resolveErrorType(exception);
            throw report(failureType, exception.statusCode(), resolveProviderFailureType(exception, failureType));
        } catch (RuntimeException exception) {
            failureType = "response".equals(phase)
                    ? AiGenerationErrorType.RESPONSE_PROCESSING_ERROR : AiGenerationErrorType.INTERNAL_ERROR;
            throw report(failureType, null, AiProviderFailureType.UNKNOWN);
        } finally {
            stageMetrics.record(failureType, System.nanoTime() - started, result, usage);
        }
    }

    public static GenerationTokenUsage tokenUsage(QwenChatClient.Usage usage, boolean thinking) {
        if (usage == null) {
            return null;
        }
        Integer candidateTokens = null;
        Integer thoughts = usage.reasoningTokens();
        if (usage.completionTokens() != null && thoughts != null && thoughts <= usage.completionTokens()) {
            candidateTokens = usage.completionTokens() - thoughts;
        } else if (!thinking && thoughts == null) {
            candidateTokens = usage.completionTokens();
        }
        return new GenerationTokenUsage(usage.promptTokens(), candidateTokens, thoughts, usage.totalTokens());
    }

    private AiGenerationException report(
            AiGenerationErrorType type,
            Integer statusCode,
            AiProviderFailureType providerFailureType
    ) {
        AiGenerationException failure = new AiGenerationException(type, "Qwen 스토리보드 생성에 실패했습니다.",
                null, providerFailureType);
        ExternalApiFailure metadata = new ExternalApiFailure("qwen", OPERATION, type.name(),
                statusCode == null ? null : statusCode.toString(), null, null);
        if (type == AiGenerationErrorType.RESPONSE_PROCESSING_ERROR || type == AiGenerationErrorType.INTERNAL_ERROR) {
            logger.error(metadata, failure);
        } else {
            logger.warn(metadata, failure);
        }
        return failure;
    }

    private static AiGenerationErrorType resolveErrorType(QwenChatException exception) {
        return switch (exception.kind()) {
            case TIMEOUT -> AiGenerationErrorType.TIMEOUT;
            case NETWORK -> AiGenerationErrorType.PROVIDER_ERROR;
            case RESPONSE -> AiGenerationErrorType.RESPONSE_PROCESSING_ERROR;
            case TRUNCATED -> AiGenerationErrorType.OUTPUT_TRUNCATED;
            case HTTP -> httpErrorType(exception.statusCode());
        };
    }

    private static AiProviderFailureType resolveProviderFailureType(
            QwenChatException exception,
            AiGenerationErrorType errorType
    ) {
        if ((exception.kind() == QwenChatException.Kind.NETWORK || exception.kind() == QwenChatException.Kind.TIMEOUT)
                && Thread.currentThread().isInterrupted() || Integer.valueOf(499).equals(exception.statusCode())) {
            return AiProviderFailureType.CANCELLED;
        }
        if (errorType != AiGenerationErrorType.PROVIDER_ERROR) {
            return AiProviderFailureType.UNKNOWN;
        }
        if (exception.kind() == QwenChatException.Kind.NETWORK) {
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
