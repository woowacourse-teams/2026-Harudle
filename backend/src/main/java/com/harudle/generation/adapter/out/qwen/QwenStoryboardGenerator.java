package com.harudle.generation.adapter.out.qwen;

import com.harudle.common.logging.ExternalApiFailure;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.adapter.out.storyboard.StoryboardJsonCodec;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
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

    public QwenStoryboardGenerator(QwenChatClient client, QwenStoryboardSettings settings,
                                   StoryboardJsonCodec codec, ExternalApiLogger logger) {
        this.client = Objects.requireNonNull(client);
        this.settings = Objects.requireNonNull(settings);
        this.codec = Objects.requireNonNull(codec);
        this.logger = Objects.requireNonNull(logger);
        this.requestFactory = new QwenStoryboardRequestFactory(settings, codec);
    }

    @Override
    public GeneratedStoryboard generate(StoryboardGenerationRequest request) {
        String phase = "preparation";
        try {
            QwenChatClient.Request prepared = requestFactory.create(request);
            phase = "provider";
            QwenChatClient.Completion result = client.generate(prepared);
            phase = "response";
            Storyboard storyboard = codec.decode(result.content());
            return new GeneratedStoryboard(storyboard, tokenUsage(result.usage(), settings.thinking()));
        } catch (QwenChatException exception) {
            throw report(resolveErrorType(exception), exception.statusCode());
        } catch (RuntimeException exception) {
            AiGenerationErrorType type = "response".equals(phase)
                    ? AiGenerationErrorType.RESPONSE_PROCESSING_ERROR : AiGenerationErrorType.INTERNAL_ERROR;
            throw report(type, null);
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

    private AiGenerationException report(AiGenerationErrorType type, Integer statusCode) {
        AiGenerationException failure = new AiGenerationException(type, "Qwen 스토리보드 생성에 실패했습니다.");
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
