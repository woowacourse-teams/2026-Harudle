import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.adapter.out.gemini.DiaryImagePromptRenderer;
import com.harudle.generation.adapter.out.gemini.GeminiDiaryImageGenerator;
import com.harudle.generation.adapter.out.gemini.GeminiExceptionTranslator;
import com.harudle.generation.adapter.out.gemini.GeminiFailureReporter;
import com.harudle.generation.adapter.out.gemini.GeminiStageMetrics;
import com.harudle.generation.adapter.out.gemini.GeminiStoryboardGenerator;
import com.harudle.generation.adapter.out.gemini.client.GeminiStoryboardCallScope;
import com.harudle.generation.adapter.out.gemini.client.VertexGeminiClientFactory;
import com.harudle.generation.adapter.out.gemini.client.ExpressGeminiClientFactory;
import com.harudle.generation.adapter.out.qwen.QwenChatClient;
import com.harudle.generation.adapter.out.qwen.QwenStageMetrics;
import com.harudle.generation.adapter.out.qwen.QwenStoryboardGenerator;
import com.harudle.generation.adapter.out.qwen.QwenStoryboardSettings;
import com.harudle.generation.adapter.out.storyboard.StoryboardJsonCodec;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import com.harudle.generation.adapter.out.storyboard.StoryboardResponseMapper;
import com.harudle.generation.config.GeminiGenerationProperties;
import com.harudle.generation.config.StoryboardProviderProperties;
import com.harudle.generation.diary.service.port.dto.DiaryImageGenerationRequest;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import com.harudle.generation.diary.service.storyboard.StoryboardAttemptRunner;
import com.harudle.generation.diary.service.storyboard.StoryboardGenerationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

public class PrepareServiceInput {
    static String env(String key, String fallback) { return System.getenv().getOrDefault(key, fallback); }
    static int number(String key, int fallback) { return Integer.parseInt(env(key, "" + fallback)); }
    static Duration duration(String value) {
        if (value.endsWith("ms")) return Duration.ofMillis(Long.parseLong(value.substring(0, value.length()-2)));
        if (value.endsWith("s")) return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length()-1)));
        if (value.endsWith("m")) return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length()-1)));
        return Duration.parse(value);
    }
    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static String hash(String value) throws Exception { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var input = mapper.readTree(Files.readString(Path.of("/diagnostic/input.json")));
        String provider = env("STORYBOARD_PROVIDER", "gemini");
        String fallback = env("STORYBOARD_FALLBACK_PROVIDER", "none");
        if (!provider.equals("gemini") || !(fallback.equals("none") || fallback.equals("qwen"))) {
            throw new IllegalStateException("Unexpected inspected storyboard provider configuration");
        }
        var providerProperties = new StoryboardProviderProperties(provider, fallback,
            duration(env("STORYBOARD_PRIMARY_TIMEOUT", "60s")), duration(env("STORYBOARD_FALLBACK_TIMEOUT", "60s")));
        var properties = new GeminiGenerationProperties(
            env("GEMINI_STORYBOARD_MODEL", "gemini-3.5-flash"), env("GEMINI_IMAGE_MODEL", "gemini-3.1-flash-image"),
            env("GEMINI_STORYBOARD_THINKING_LEVEL", "high"), env("GEMINI_IMAGE_ASPECT_RATIO", "1:1"),
            number("GEMINI_MAX_OUTPUT_TOKENS", 8192), number("GEMINI_RETRY_ATTEMPTS", 3),
            duration(env("GEMINI_REQUEST_TIMEOUT", "180s")));
        var scope = new GeminiStoryboardCallScope();
        var timeout = providerProperties.primaryTimeout();
        var http = HttpOptions.builder().timeout(Math.toIntExact(timeout.toMillis()))
            .retryOptions(HttpRetryOptions.builder().attempts(1).httpStatusCodes(List.of()).build()).build();
        var clientOptions = ClientOptions.builder().customHttpClient(
            singleAttemptTransport(timeout).eventListenerFactory(scope).build()).build();
        Client client;
        if (env("GEMINI_AUTH_MODE", "express").equals("vertex")) {
            GoogleCredentials credentials;
            try (var stream = Files.newInputStream(Path.of(env("GOOGLE_APPLICATION_CREDENTIALS", "")))) {
                credentials = GoogleCredentials.fromStream(stream);
            }
            client = new VertexGeminiClientFactory(env("GOOGLE_CLOUD_PROJECT", ""),
                env("GOOGLE_CLOUD_LOCATION", "global"), credentials).create(http, clientOptions);
        } else {
            client = new ExpressGeminiClientFactory(env("GOOGLE_API_KEY", env("GEMINI_API_KEY", "")))
                .create(http, clientOptions);
        }
        var registry = new SimpleMeterRegistry();
        var qwen = fallback.equals("qwen") ? new QwenChatClient(
            QwenChatClient.httpClient(providerProperties.fallbackTimeout()), mapper, env("QWEN_TOKEN_PLAN_API_KEY", "")) : null;
        try (client; qwen; var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var reporter = new GeminiFailureReporter(new GeminiExceptionTranslator(), new ExternalApiLogger());
            var metrics = new GeminiStageMetrics(registry);
            var generator = new GeminiStoryboardGenerator(client.models, properties, mapper,
                new StoryboardResponseMapper(), reporter, metrics, scope);
            Optional<StoryboardGenerator> backup = Optional.empty();
            if (qwen != null) {
                var model = env("QWEN_STORYBOARD_MODEL", "qwen3.8-max");
                var settings = new QwenStoryboardSettings(model, number("QWEN_MAX_OUTPUT_TOKENS", 8192),
                    true, number("QWEN_THINKING_BUDGET", 1024), false, providerProperties.fallbackTimeout());
                backup = Optional.of(new QwenStoryboardGenerator(qwen, settings,
                    new StoryboardJsonCodec(mapper, new StoryboardResponseMapper()),
                    new ExternalApiLogger(), new QwenStageMetrics(registry, model)));
            }
            var service = new StoryboardGenerationService(generator, backup,
                new StoryboardAttemptRunner(executor), providerProperties);
            String diary = input.get("diaryText").asText();
            String storyboardPrompt = input.get("storyboardPrompt").asText();
            long started = System.nanoTime();
            var generated = service.generate(new StoryboardGenerationRequest(diary, storyboardPrompt));
            long storyboardElapsedMs = (System.nanoTime() - started) / 1_000_000;
            String referenceFile = input.get("referenceFile").asText();
            byte[] referenceBytes = Files.readAllBytes(Path.of("/diagnostic", referenceFile));
            var request = new DiaryImageGenerationRequest(generated.storyboard(), input.get("imageStyle").asText(),
                new ReferenceImage(new ByteArrayResource(referenceBytes),
                    MediaType.parseMediaType(input.get("referenceMediaType").asText())));
            var renderer = new DiaryImagePromptRenderer();
            var imageGenerator = new GeminiDiaryImageGenerator(client.models, properties, renderer, reporter, metrics);
            // Prepare exactly the existing image-provider request; do not make a Gemini image request.
            var prepare = GeminiDiaryImageGenerator.class.getDeclaredMethod("prepareRequest", DiaryImageGenerationRequest.class);
            prepare.setAccessible(true);
            Object prepared = prepare.invoke(imageGenerator, request);
            var contentAccessor = prepared.getClass().getDeclaredMethod("content");
            var configAccessor = prepared.getClass().getDeclaredMethod("config");
            contentAccessor.setAccessible(true);
            configAccessor.setAccessible(true);
            Content content = (Content) contentAccessor.invoke(prepared);
            GenerateContentConfig config = (GenerateContentConfig) configAccessor.invoke(prepared);
            var parts = content.parts().orElseThrow();
            String actualStyle = config.systemInstruction().orElseThrow().parts().orElseThrow().getFirst().text().orElseThrow();
            String referenceInstruction = parts.getFirst().text().orElseThrow();
            String finalTask = parts.getLast().text().orElseThrow();
            byte[] actualReference = parts.get(1).inlineData().orElseThrow().data().orElseThrow();
            if (!actualStyle.equals(request.imageStylePromptText())
                || !finalTask.equals("[Final Task]\n" + renderer.render(generated.storyboard()))
                || !hash(actualReference).equals(hash(referenceBytes))) throw new IllegalStateException("Input mismatch");
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("kind", "harudle-service-image-input-v1");
            result.put("generatedAt", Instant.now().toString());
            result.put("environment", env("DEPLOY_ENV", "unknown"));
            result.put("promptId", input.get("promptId").asLong());
            result.put("providerInfo", generated.providerInfo());
            result.put("configuredPrimaryProvider", provider);
            result.put("configuredFallbackProvider", fallback);
            result.put("storyboardElapsedMs", storyboardElapsedMs);
            result.put("diaryText", diary);
            result.put("storyboard", generated.storyboard());
            result.put("systemInstruction", actualStyle);
            result.put("referenceInstruction", referenceInstruction);
            result.put("finalTask", finalTask);
            result.put("referenceFile", referenceFile);
            result.put("referenceMediaType", input.get("referenceMediaType").asText());
            result.put("imageAspectRatio", properties.imageAspectRatio());
            result.put("hashes", Map.of("storyboardPrompt", hash(storyboardPrompt), "imageStyle", hash(actualStyle),
                "reference", hash(actualReference), "renderedFinalTask", hash(finalTask)));
            result.put("lineage", List.of("GenerationPrompt/latest", "StoryboardGenerationService",
                "GeminiStoryboardGenerator", "DiaryImageGenerationRequest", "GeminiDiaryImageGenerator.prepareRequest",
                "DiaryImagePromptRenderer"));
            result.put("applicationRecordsWritten", false);
            System.out.println("SERVICE_INPUT " + mapper.writeValueAsString(result));
        } finally {
            registry.close();
        }
    }
    private static okhttp3.OkHttpClient.Builder singleAttemptTransport(Duration timeout) throws Exception {
        Class<?> transport;
        try {
            transport = Class.forName("com.harudle.generation.adapter.out.http.SingleAttemptHttpTransport");
        } catch (ClassNotFoundException exception) {
            transport = Class.forName("com.harudle.generation.adapter.out.storyboard.StoryboardHttpTransport");
        }
        return (okhttp3.OkHttpClient.Builder) transport.getMethod("builder", Duration.class).invoke(null, timeout);
    }

}
