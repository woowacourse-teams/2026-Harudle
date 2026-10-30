package com.harudle.generation.adapter.out.openai;

import com.harudle.generation.adapter.out.gemini.DiaryImagePromptRenderer;
import com.harudle.generation.adapter.out.http.SingleAttemptHttpTransport;
import com.harudle.generation.adapter.out.image.DiaryImagePrompt;
import com.harudle.generation.config.ImageProviderProperties;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import com.harudle.generation.diary.service.port.dto.DiaryImageGenerationRequest;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

public final class OpenAiDiaryImageGenerator implements DiaryImageGenerator {

    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 28 * 1024 * 1024;
    private static final String ENDPOINT = "https://api.openai.com/v1/images/edits";
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private final ImageProviderProperties properties;
    private final DiaryImagePromptRenderer renderer;
    private final ObjectMapper objectMapper;
    private final MeterRegistry metrics;
    private final Call.Factory calls;
    private final OpenAiExceptionTranslator exceptionTranslator = new OpenAiExceptionTranslator();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OpenAiDiaryImageGenerator.class);

    public OpenAiDiaryImageGenerator(
            ImageProviderProperties properties, DiaryImagePromptRenderer renderer,
            ObjectMapper objectMapper, MeterRegistry metrics
    ) {
        this(properties, renderer, objectMapper, metrics,
                SingleAttemptHttpTransport.builder(properties.openaiTimeout()).build());
    }

    OpenAiDiaryImageGenerator(
            ImageProviderProperties properties, DiaryImagePromptRenderer renderer,
            ObjectMapper objectMapper, MeterRegistry metrics, Call.Factory calls
    ) {
        if (!properties.openaiEnabled() || !properties.isOpenaiConnectionValid() || !properties.isOpenaiTimeoutValid()) {
            throw new IllegalArgumentException("유효한 OpenAI 이미지 fallback 설정이 필요합니다.");
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
            checkInterrupted();
            GeneratedImage result = invoke(prepareRequest(request));
            checkInterrupted();
            outcome = "success";
            return result;
        } finally {
            try {
                metrics.timer("harudle.image.provider.duration", "provider", "openai", "model", properties.openaiModel(), "outcome", outcome)
                        .record(Duration.ofNanos(System.nanoTime() - started));
            } catch (RuntimeException exception) {
                log.warn("event=metrics_recording_failed component=openai exceptionType={}",
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
            String prompt = request.imageStylePromptText() + "\n\n"
                    + DiaryImagePrompt.REFERENCE_INSTRUCTION.replace("the system instruction", "the style instructions above")
                    + "\n\n" + DiaryImagePrompt.finalTask(request, renderer);
            if (prompt.codePointCount(0, prompt.length()) > 32000) {
                throw new IllegalArgumentException("OpenAI 이미지 지시문은 32000자 이하여야 합니다.");
            }
            String mediaType = request.referenceImage().mediaType().toString();
            if (!List.of("image/png", "image/jpeg", "image/webp").contains(mediaType)) {
                throw new IllegalArgumentException("OpenAI 참조 이미지는 PNG, JPEG, WebP만 지원합니다.");
            }
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                    "model", properties.openaiModel(),
                    "prompt", prompt,
                    "images", List.of(Map.of("image_url", "data:" + mediaType + ";base64,"
                            + Base64.getEncoder().encodeToString(reference))),
                    "n", 1,
                    "size", properties.openaiSize(),
                    "quality", properties.openaiQuality(),
                    "output_format", "png"
            ));
            if (body.length > MAX_BYTES) {
                throw new IllegalArgumentException("OpenAI 이미지 요청은 20MiB 이하여야 합니다.");
            }
            return new Request.Builder()
                    .url(ENDPOINT)
                    .header("Authorization", "Bearer " + properties.openaiApiKey())
                    .post(RequestBody.create(body, okhttp3.MediaType.get("application/json; charset=utf-8"))).build();
        } catch (IOException | RuntimeException exception) {
            throw exceptionTranslator.preparationFailure(exception);
        }
    }

    private Call newCall(Request request) {
        try {
            return calls.newCall(request);
        } catch (RuntimeException exception) {
            throw exceptionTranslator.providerFailure(exception);
        }
    }

    private Response callProvider(Call call) {
        try {
            return call.execute();
        } catch (IOException | RuntimeException exception) {
            throw exceptionTranslator.providerFailure(exception);
        }
    }

    private GeneratedImage invoke(Request request) {
        Call call = newCall(request);
        FutureTask<GeneratedImage> task = new FutureTask<>(() -> readResponse(call));
        try {
            checkInterrupted();
            // Socket reads need explicit Call cancellation when the waiting caller is interrupted.
            Thread.startVirtualThread(task);
            return task.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exceptionTranslator.providerFailure(exception);
        } catch (ExecutionException exception) {
            checkInterrupted();
            if (exception.getCause() instanceof Error error) {
                throw error;
            }
            throw exceptionTranslator.providerFailure(exception.getCause());
        } finally {
            if (Thread.currentThread().isInterrupted()) {
                call.cancel();
                task.cancel(true);
            }
        }
    }

    private void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw exceptionTranslator.providerFailure(new CancellationException());
        }
    }

    private GeneratedImage readResponse(Call call) {
        try (Response response = callProvider(call)) {
            if (response.code() != 200) {
                throw exceptionTranslator.httpFailure(response.code());
            }
            if (response.body() == null || response.body().contentType() == null
                    || !"application/json".equals(response.body().contentType().type() + "/"
                            + response.body().contentType().subtype())) {
                throw new IllegalStateException("OpenAI API의 이미지 응답이 유효하지 않습니다.");
            }
            byte[] json = response.body().byteStream().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (json.length > MAX_RESPONSE_BYTES) {
                throw new IllegalStateException("OpenAI 이미지 응답이 크기 제한을 초과했습니다.");
            }
            var encoded = objectMapper.readTree(json).path("data").path(0).path("b64_json");
            if (!encoded.isString() || encoded.asString().isBlank()) {
                throw new IllegalStateException("OpenAI 응답에 이미지가 없습니다.");
            }
            byte[] image = Base64.getDecoder().decode(encoded.asString());
            if (!isPng(image)) {
                throw new IllegalStateException("OpenAI API의 이미지 응답이 유효하지 않습니다.");
            }
            return new GeneratedImage(new ByteArrayResource(image), MediaType.IMAGE_PNG);
        } catch (IOException exception) {
            throw exceptionTranslator.providerFailure(exception);
        } catch (RuntimeException exception) {
            throw exceptionTranslator.responseFailure(exception);
        }
    }

    private static boolean isPng(byte[] image) {
        if (image.length < 33 || image.length > MAX_BYTES
                || !Arrays.equals(Arrays.copyOf(image, 8), PNG_SIGNATURE)) {
            return false;
        }
        int width = ByteBuffer.wrap(image, 16, 4).getInt();
        int height = ByteBuffer.wrap(image, 20, 4).getInt();
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096
                || (long) width * height > 4_194_304) {
            return false;
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(image))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return false;
            }
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return reader.read(0) != null;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }
}
