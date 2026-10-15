package com.harudle.generation.adapter.out.codex;

import com.harudle.generation.adapter.out.gemini.DiaryImagePromptRenderer;
import com.harudle.generation.adapter.out.http.SingleAttemptHttpTransport;
import com.harudle.generation.adapter.out.image.DiaryImagePrompt;
import com.harudle.generation.config.ImageProviderProperties;
import com.harudle.generation.diary.service.exception.AiGenerationErrorType;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.exception.AiProviderFailureType;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import com.harudle.generation.diary.service.port.dto.DiaryImageGenerationRequest;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

public final class CodexDiaryImageGenerator implements DiaryImageGenerator {

    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private final ImageProviderProperties properties;
    private final DiaryImagePromptRenderer renderer;
    private final ObjectMapper objectMapper;
    private final MeterRegistry metrics;
    private final OkHttpClient http;

    public CodexDiaryImageGenerator(
            ImageProviderProperties properties, DiaryImagePromptRenderer renderer,
            ObjectMapper objectMapper, MeterRegistry metrics
    ) {
        if (!properties.codexEnabled() || !properties.isCodexConnectionValid() || !properties.isCodexTimeoutValid()) {
            throw new IllegalArgumentException("유효한 Codex 이미지 fallback 설정이 필요합니다.");
        }
        this.properties = properties;
        this.renderer = renderer;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.http = SingleAttemptHttpTransport.builder(properties.codexTimeout()).build();
    }

    @Override
    public GeneratedImage generate(DiaryImageGenerationRequest request) {
        long started = System.nanoTime();
        String outcome = "failure";
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw failure(AiGenerationErrorType.PROVIDER_ERROR, AiProviderFailureType.CANCELLED, null);
            }
            GeneratedImage result = invoke(prepareBody(request));
            outcome = "success";
            return result;
        } finally {
            metrics.timer("harudle.image.provider.duration", "provider", "codex", "outcome", outcome)
                    .record(Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private byte[] prepareBody(DiaryImageGenerationRequest request) {
        try (var input = request.referenceImage().resource().getInputStream()) {
            byte[] reference = input.readNBytes(MAX_BYTES + 1);
            if (reference.length == 0 || reference.length > MAX_BYTES) {
                throw new IllegalArgumentException("참조 이미지 크기가 유효하지 않습니다.");
            }
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                    "systemInstruction", request.imageStylePromptText(),
                    "referenceInstruction", DiaryImagePrompt.REFERENCE_INSTRUCTION,
                    "finalTask", DiaryImagePrompt.finalTask(request, renderer),
                    "reference", Map.of(
                            "mediaType", request.referenceImage().mediaType().toString(),
                            "base64", Base64.getEncoder().encodeToString(reference))
            ));
            if (body.length > MAX_BYTES) {
                throw new IllegalArgumentException("Codex 이미지 요청은 20MiB 이하여야 합니다.");
            }
            return body;
        } catch (IOException | RuntimeException exception) {
            throw failure(AiGenerationErrorType.INTERNAL_ERROR, AiProviderFailureType.UNKNOWN, exception);
        }
    }

    private GeneratedImage invoke(byte[] body) {
        RequestBody singleAttemptBody = RequestBody.create(
                body, okhttp3.MediaType.get("application/json; charset=utf-8")
        );
        Request request = new Request.Builder()
                .url(properties.codexUrl().resolve("/v1/images/generations").toString())
                .header("Authorization", "Bearer " + properties.codexToken())
                .post(singleAttemptBody).build();
        Call call = http.newCall(request);
        try (Response response = call.execute()) {
            if (response.code() != 200) {
                throw statusFailure(response.code());
            }
            if (response.body() == null || response.body().contentType() == null
                    || !"image/png".equals(response.body().contentType().toString())) {
                throw failure(AiGenerationErrorType.RESPONSE_PROCESSING_ERROR, AiProviderFailureType.UNKNOWN, null);
            }
            byte[] image = response.body().byteStream().readNBytes(MAX_BYTES + 1);
            if (!isPng(image)) {
                throw failure(AiGenerationErrorType.RESPONSE_PROCESSING_ERROR, AiProviderFailureType.UNKNOWN, null);
            }
            return new GeneratedImage(new ByteArrayResource(image), MediaType.IMAGE_PNG);
        } catch (IOException exception) {
            boolean cancelled = Thread.currentThread().isInterrupted();
            throw failure(cancelled ? AiGenerationErrorType.PROVIDER_ERROR
                            : exception instanceof InterruptedIOException ? AiGenerationErrorType.TIMEOUT
                            : AiGenerationErrorType.PROVIDER_ERROR,
                    cancelled ? AiProviderFailureType.CANCELLED : AiProviderFailureType.NETWORK_ERROR, exception);
        }
    }

    private static boolean isPng(byte[] image) {
        return image.length >= 33 && image.length <= MAX_BYTES
                && Arrays.equals(Arrays.copyOf(image, 8), PNG_SIGNATURE)
                && ByteBuffer.wrap(image, 16, 8).getInt() > 0
                && ByteBuffer.wrap(image, 20, 4).getInt() > 0;
    }

    private static AiGenerationException statusFailure(int status) {
        return switch (status) {
            case 429 -> failure(AiGenerationErrorType.RATE_LIMITED, AiProviderFailureType.UNKNOWN, null);
            case 408, 504 -> failure(AiGenerationErrorType.TIMEOUT, AiProviderFailureType.UNKNOWN, null);
            case 401, 403 -> failure(AiGenerationErrorType.PROVIDER_ERROR,
                    AiProviderFailureType.AUTH_CONFIGURATION_ERROR, null);
            case 400, 413, 415, 422 -> failure(AiGenerationErrorType.PROVIDER_ERROR,
                    AiProviderFailureType.REQUEST_REJECTED, null);
            default -> failure(AiGenerationErrorType.PROVIDER_ERROR,
                    status >= 500 ? AiProviderFailureType.TRANSIENT_SERVER_ERROR : AiProviderFailureType.UNKNOWN, null);
        };
    }

    private static AiGenerationException failure(
            AiGenerationErrorType type, AiProviderFailureType detail, Throwable cause
    ) {
        return new AiGenerationException(type, "Codex 이미지 생성 요청에 실패했습니다.", cause, detail);
    }
}
