package com.harudle.generation.adapter.out.codex;

import com.harudle.generation.adapter.out.gemini.DiaryImagePromptRenderer;
import com.harudle.generation.adapter.out.http.SingleAttemptHttpTransport;
import com.harudle.generation.adapter.out.image.DiaryImagePrompt;
import com.harudle.generation.config.ImageProviderProperties;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import com.harudle.generation.diary.service.port.dto.DiaryImageGenerationRequest;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import okhttp3.Call;
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
    private final Call.Factory calls;
    private final CodexExceptionTranslator exceptionTranslator = new CodexExceptionTranslator();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CodexDiaryImageGenerator.class);

    public CodexDiaryImageGenerator(
            ImageProviderProperties properties, DiaryImagePromptRenderer renderer,
            ObjectMapper objectMapper, MeterRegistry metrics
    ) {
        this(properties, renderer, objectMapper, metrics,
                SingleAttemptHttpTransport.builder(properties.codexTimeout()).build());
    }

    CodexDiaryImageGenerator(
            ImageProviderProperties properties, DiaryImagePromptRenderer renderer,
            ObjectMapper objectMapper, MeterRegistry metrics, Call.Factory calls
    ) {
        if (!properties.codexEnabled() || !properties.isCodexConnectionValid() || !properties.isCodexTimeoutValid()) {
            throw new IllegalArgumentException("유효한 Codex 이미지 fallback 설정이 필요합니다.");
        }
        this.properties = properties;
        this.renderer = renderer;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.calls = calls;
    }

    @Override
    public GeneratedImage generate(DiaryImageGenerationRequest request) {
        long started = System.nanoTime();
        String outcome = "failure";
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw exceptionTranslator.providerFailure(new java.util.concurrent.CancellationException());
            }
            GeneratedImage result = invoke(prepareRequest(request));
            outcome = "success";
            return result;
        } finally {
            try {
                metrics.timer("harudle.image.provider.duration", "provider", "codex", "outcome", outcome)
                        .record(Duration.ofNanos(System.nanoTime() - started));
            } catch (RuntimeException exception) {
                log.warn("event=metrics_recording_failed component=codex exceptionType={}",
                        exception.getClass().getSimpleName());
            }
        }
    }

    private Request prepareRequest(DiaryImageGenerationRequest request) {
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
            return new Request.Builder()
                    .url(properties.codexUrl().resolve("/v1/images/generations").toString())
                    .header("Authorization", "Bearer " + properties.codexToken())
                    .post(RequestBody.create(body, okhttp3.MediaType.get("application/json; charset=utf-8"))).build();
        } catch (IOException | RuntimeException exception) {
            throw exceptionTranslator.preparationFailure(exception);
        }
    }

    private Response callProvider(Request request) {
        try {
            return calls.newCall(request).execute();
        } catch (IOException | RuntimeException exception) {
            throw exceptionTranslator.providerFailure(exception);
        }
    }

    private GeneratedImage invoke(Request request) {
        try (Response response = callProvider(request)) {
            if (response.code() != 200) {
                throw exceptionTranslator.httpFailure(response.code());
            }
            if (response.body() == null || response.body().contentType() == null
                    || !"image/png".equals(response.body().contentType().toString())) {
                throw new IllegalStateException("Codex worker의 이미지 응답이 유효하지 않습니다.");
            }
            byte[] image = response.body().byteStream().readNBytes(MAX_BYTES + 1);
            if (!isPng(image)) {
                throw new IllegalStateException("Codex worker의 이미지 응답이 유효하지 않습니다.");
            }
            return new GeneratedImage(new ByteArrayResource(image), MediaType.IMAGE_PNG);
        } catch (IOException exception) {
            throw exceptionTranslator.providerFailure(exception);
        } catch (RuntimeException exception) {
            throw exceptionTranslator.responseFailure(exception);
        }
    }

    private static boolean isPng(byte[] image) {
        return image.length >= 33 && image.length <= MAX_BYTES
                && Arrays.equals(Arrays.copyOf(image, 8), PNG_SIGNATURE)
                && ByteBuffer.wrap(image, 16, 8).getInt() > 0
                && ByteBuffer.wrap(image, 20, 4).getInt() > 0;
    }

}
